package com.weavo.worker.infrastructure;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.sqs.SqsClient;

/** Owns the worker's clients, queue URLs, delivery receipts and immutable result objects. */
public final class AwsJobTransport implements AutoCloseable {
  private final SqsClient sqs;
  private final S3Client s3;
  private final String bucket;
  private final List<String> queueNames;
  private final String resultQueueName;
  private List<String> queues;
  private String resultQueue;
  private int priorityStreak;

  AwsJobTransport(
      SqsClient sqs, S3Client s3, String bucket, List<String> queueNames, String resultQueueName) {
    if (queueNames.isEmpty() || queueNames.size() > 2 || queueNames.contains(resultQueueName)) {
      throw new IllegalArgumentException("Expected a worker queue or a pair of collection queues");
    }
    if (queueNames.size() != queueNames.stream().distinct().count()) {
      throw new IllegalArgumentException("Priority and scheduled queues must be distinct");
    }
    this.sqs = sqs;
    this.s3 = s3;
    this.bucket = bucket;
    this.queueNames = List.copyOf(queueNames);
    this.resultQueueName = resultQueueName;
  }

  public static AwsJobTransport open(
      String region,
      URI localEndpoint,
      String bucket,
      List<String> queueNames,
      String resultQueueName,
      Duration timeout) {
    var sqsBuilder = SqsClient.builder().region(Region.of(region));
    var s3Builder = S3Client.builder().region(Region.of(region));
    // The worker rechecks its lease between delivery attempts.
    sqsBuilder.overrideConfiguration(
        options -> options.apiCallTimeout(timeout).retryStrategy(retry -> retry.maxAttempts(1)));
    s3Builder.overrideConfiguration(
        options -> options.apiCallTimeout(timeout).retryStrategy(retry -> retry.maxAttempts(1)));
    if (localEndpoint != null) {
      var credentials =
          StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
      sqsBuilder.endpointOverride(localEndpoint).credentialsProvider(credentials);
      s3Builder
          .endpointOverride(localEndpoint)
          .credentialsProvider(credentials)
          .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
    }
    var sqs = sqsBuilder.build();
    try {
      var s3 = s3Builder.build();
      try {
        return new AwsJobTransport(sqs, s3, bucket, queueNames, resultQueueName);
      } catch (RuntimeException exception) {
        s3.close();
        throw exception;
      }
    } catch (RuntimeException exception) {
      sqs.close();
      throw exception;
    }
  }

  public void prepare() {
    queues = queueNames.stream().map(this::queueUrl).toList();
    resultQueue = queueUrl(resultQueueName);
  }

  public Delivery receive(int visibilitySeconds) {
    if (queues.size() == 1) {
      return receiveFrom(0, 5, visibilitySeconds);
    }
    // Probe both queues before waiting. Prefer scheduled work after three priority starts.
    int preferred = priorityStreak == 3 ? 1 : 0;
    var delivery = receiveFrom(preferred, 0, visibilitySeconds);
    if (delivery == null) {
      delivery = receiveFrom(1 - preferred, 0, visibilitySeconds);
    }
    return delivery != null ? delivery : receiveFrom(preferred, 2, visibilitySeconds);
  }

  /** Updates this worker's queue preference only when the claimed job starts running. */
  public void recordExecutionStart(Delivery delivery) {
    if (queues.size() == 2) {
      priorityStreak =
          queues.getFirst().equals(delivery.queueUrl()) ? Math.min(priorityStreak + 1, 3) : 0;
    }
  }

  public void changeVisibility(Delivery delivery, int seconds) {
    sqs.changeMessageVisibility(request ->
        request
            .queueUrl(delivery.queueUrl())
            .receiptHandle(delivery.receipt())
            .visibilityTimeout(seconds));
  }

  public void store(String key, String json) {
    storeObject(key, "application/json", json.getBytes(StandardCharsets.UTF_8));
  }

  public void storeObject(String key, String contentType, byte[] content) {
    try {
      s3.putObject(
          request -> request.bucket(bucket).key(key).contentType(contentType).ifNoneMatch("*"),
          RequestBody.fromBytes(content));
    } catch (S3Exception exception) {
      if (exception.statusCode() != 412) {
        throw exception;
      }
      // A prior PUT may have succeeded without returning a response.
      byte[] recorded =
          s3.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asByteArray();
      if (!Arrays.equals(content, recorded)) {
        throw new IllegalStateException("Attempt object already contains different bytes");
      }
    }
  }

  public byte[] readObject(String key) {
    return s3.getObjectAsBytes(request -> request.bucket(bucket).key(key)).asByteArray();
  }

  public void publish(String json) {
    sqs.sendMessage(request -> request.queueUrl(resultQueue).messageBody(json));
  }

  public void delete(Delivery delivery) {
    sqs.deleteMessage(
        request -> request.queueUrl(delivery.queueUrl()).receiptHandle(delivery.receipt()));
  }

  @Override
  public void close() {
    try {
      sqs.close();
    } finally {
      s3.close();
    }
  }

  private String queueUrl(String name) {
    return sqs.getQueueUrl(request -> request.queueName(name)).queueUrl();
  }

  private Delivery receiveFrom(int index, int waitSeconds, int visibilitySeconds) {
    if (Thread.currentThread().isInterrupted()) {
      return null;
    }
    String url = queues.get(index);
    var response =
        sqs.receiveMessage(request ->
            request
                .queueUrl(url)
                .maxNumberOfMessages(1)
                .waitTimeSeconds(waitSeconds)
                .visibilityTimeout(visibilitySeconds));
    if (!response.hasMessages()) {
      return null;
    }
    var message = response.messages().getFirst();
    return new Delivery(url, message.receiptHandle(), message.body());
  }

  public record Delivery(String queueUrl, String receipt, String body) {}
}
