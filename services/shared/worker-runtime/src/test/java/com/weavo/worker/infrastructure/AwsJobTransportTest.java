package com.weavo.worker.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mockito.Answers;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlResponse;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageResponse;

class AwsJobTransportTest {
  private final SqsClient sqs = mock(SqsClient.class, Answers.CALLS_REAL_METHODS);
  private final S3Client s3 = mock(S3Client.class, Answers.CALLS_REAL_METHODS);

  @Test
  @EnabledIfEnvironmentVariable(named = "WEAVO_QUEUE_TEST_AWS", matches = ".+")
  void countsStartsPerWorkerAcrossTwoRealQueues() {
    URI endpoint = URI.create(System.getenv("WEAVO_QUEUE_TEST_AWS"));
    String prefix = "weavo-queue-test-" + UUID.randomUUID();
    var names = List.of(prefix + "-priority", prefix + "-scheduled");
    String results = prefix + "-results";
    var queueUrls = new ArrayList<String>();
    try (var client =
        SqsClient.builder()
            .endpointOverride(endpoint)
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .build()) {
      try {
        for (String name : List.of(names.getFirst(), names.getLast(), results)) {
          queueUrls.add(client.createQueue(request -> request.queueName(name)).queueUrl());
        }
        try (var first =
                AwsJobTransport.open(
                    "us-east-1", endpoint, "unused", names, results, Duration.ofSeconds(5));
            var second =
                AwsJobTransport.open(
                    "us-east-1", endpoint, "unused", names, results, Duration.ofSeconds(5))) {
          first.prepare();
          second.prepare();
          for (int index = 0; index < 5; index++) {
            client.sendMessage(
                request -> request.queueUrl(queueUrls.getFirst()).messageBody("priority"));
          }
          client.sendMessage(
              request -> request.queueUrl(queueUrls.get(1)).messageBody("scheduled"));

          var unclaimed = first.receive(30);
          assertThat(unclaimed.body()).isEqualTo("priority");
          first.delete(unclaimed);
          for (int index = 0; index < 3; index++) {
            var delivery = first.receive(30);
            assertThat(delivery.body()).isEqualTo("priority");
            first.recordExecutionStart(delivery);
            first.delete(delivery);
          }
          var otherWorker = second.receive(30);
          assertThat(otherWorker.body()).isEqualTo("priority");
          second.recordExecutionStart(otherWorker);
          second.delete(otherWorker);

          var scheduled = first.receive(30);
          assertThat(scheduled.body()).isEqualTo("scheduled");
          first.recordExecutionStart(scheduled);
          first.delete(scheduled);
        }
      } finally {
        queueUrls.forEach(url -> client.deleteQueue(request -> request.queueUrl(url)));
      }
    }
  }

  @Test
  void startsThreePriorityDeliveriesThenOneScheduledWhileBothQueuesHaveWork() {
    var requests = new ArrayList<ReceiveMessageRequest>();
    respondToQueueLookups();
    doAnswer(
            invocation -> {
              ReceiveMessageRequest request = invocation.getArgument(0);
              requests.add(request);
              return message(request.queueUrl());
            })
        .when(sqs)
        .receiveMessage(any(ReceiveMessageRequest.class));

    var transport =
        new AwsJobTransport(sqs, s3, "bucket", List.of("priority", "scheduled"), "result");
    transport.prepare();

    for (int i = 0; i < 8; i++) {
      transport.recordExecutionStart(transport.receive(30));
    }

    assertThat(requests)
        .extracting(ReceiveMessageRequest::queueUrl)
        .containsExactly(
            "priority",
            "priority",
            "priority",
            "scheduled",
            "priority",
            "priority",
            "priority",
            "scheduled");
  }

  @Test
  void unclaimedReceiptsNeitherConsumePrioritySlotsNorResetTheScheduledTurn() {
    respondToQueueLookups();
    doAnswer(
            invocation -> {
              ReceiveMessageRequest request = invocation.getArgument(0);
              return message(request.queueUrl());
            })
        .when(sqs)
        .receiveMessage(any(ReceiveMessageRequest.class));
    var transport =
        new AwsJobTransport(sqs, s3, "bucket", List.of("priority", "scheduled"), "result");
    transport.prepare();

    for (int index = 0; index < 4; index++) {
      assertThat(transport.receive(30).queueUrl()).isEqualTo("priority");
    }
    for (int index = 0; index < 3; index++) {
      var delivery = transport.receive(30);
      assertThat(delivery.queueUrl()).isEqualTo("priority");
      transport.recordExecutionStart(delivery);
    }
    assertThat(transport.receive(30).queueUrl()).isEqualTo("scheduled");
    var next = transport.receive(30);
    assertThat(next.queueUrl()).isEqualTo("scheduled");
    transport.recordExecutionStart(next);
    assertThat(transport.receive(30).queueUrl()).isEqualTo("priority");
  }

  @Test
  void keepsStartingPriorityWorkWhenTheScheduledQueueIsEmpty() {
    respondToQueueLookups();
    doAnswer(
            invocation -> {
              ReceiveMessageRequest request = invocation.getArgument(0);
              return request.queueUrl().equals("priority")
                  ? message("priority")
                  : ReceiveMessageResponse.builder().build();
            })
        .when(sqs)
        .receiveMessage(any(ReceiveMessageRequest.class));
    var transport =
        new AwsJobTransport(sqs, s3, "bucket", List.of("priority", "scheduled"), "result");
    transport.prepare();
    for (int index = 0; index < 6; index++) {
      var delivery = transport.receive(30);
      assertThat(delivery.queueUrl()).isEqualTo("priority");
      transport.recordExecutionStart(delivery);
    }
  }

  @Test
  void usesAnAvailableQueueWhenThePreferredQueueIsEmpty() {
    respondToQueueLookups();
    doAnswer(
            invocation -> {
              ReceiveMessageRequest request = invocation.getArgument(0);
              return request.queueUrl().equals("scheduled")
                  ? message("scheduled")
                  : ReceiveMessageResponse.builder().build();
            })
        .when(sqs)
        .receiveMessage(any(ReceiveMessageRequest.class));

    var transport =
        new AwsJobTransport(sqs, s3, "bucket", List.of("priority", "scheduled"), "result");
    transport.prepare();

    assertThat(transport.receive(30).queueUrl()).isEqualTo("scheduled");
  }

  @Test
  void acceptsTheSameStoredResultButRefusesToOverwriteDifferentBytes() {
    doThrow(S3Exception.builder().statusCode(412).build())
        .when(s3)
        .putObject(any(PutObjectRequest.class), any(RequestBody.class));
    doReturn(
            ResponseBytes.fromByteArray(
                GetObjectResponse.builder().build(), "{}".getBytes(StandardCharsets.UTF_8)))
        .when(s3)
        .getObjectAsBytes(any(GetObjectRequest.class));
    var transport = new AwsJobTransport(sqs, s3, "bucket", List.of("analysis"), "result");

    transport.store("attempt/result.json", "{}");
    assertThatIllegalStateException()
        .isThrownBy(() -> transport.store("attempt/result.json", "[]"));

    verify(s3, org.mockito.Mockito.times(2))
        .putObject(
            org.mockito.ArgumentMatchers.argThat((PutObjectRequest request) ->
                request.ifNoneMatch().equals("*") && request.key().equals("attempt/result.json")),
            any(RequestBody.class));
  }

  private void respondToQueueLookups() {
    doAnswer(
            invocation -> {
              GetQueueUrlRequest request = invocation.getArgument(0);
              return GetQueueUrlResponse.builder().queueUrl(request.queueName()).build();
            })
        .when(sqs)
        .getQueueUrl(any(GetQueueUrlRequest.class));
  }

  private static ReceiveMessageResponse message(String queue) {
    return ReceiveMessageResponse.builder()
        .messages(Message.builder().receiptHandle("receipt").body(queue).build())
        .build();
  }
}
