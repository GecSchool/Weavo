package com.weavo.worker.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.RequestBody;
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
  void servesThreePriorityDeliveriesThenOneScheduledWhileBothQueuesHaveWork() {
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
      assertThat(transport.receive(30)).isNotNull();
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
