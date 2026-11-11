package com.weavo.crawler;

import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.sqs.SqsClient;

/** 명시적 연결 확인만 수행하며, 큐 메시지는 수신하거나 삭제하지 않는다. */
public final class AwsResourceChecker {
  private final WorkerSettings settings;

  public AwsResourceChecker(WorkerSettings settings) {
    this.settings = settings;
  }

  public void check() {
    settings.validate();
    var sqsBuilder = SqsClient.builder().region(Region.of(settings.region()));
    var s3Builder = S3Client.builder().region(Region.of(settings.region()));
    if (settings.isLocal()) {
      var credentials =
          StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
      var endpoint = URI.create(settings.endpoint());
      sqsBuilder.endpointOverride(endpoint).credentialsProvider(credentials);
      s3Builder
          .endpointOverride(endpoint)
          .credentialsProvider(credentials)
          .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build());
    }
    try (SqsClient sqs = sqsBuilder.build();
        S3Client s3 = s3Builder.build()) {
      checkResources(sqs, s3);
    }
  }

  void checkResources(SqsClient sqs, S3Client s3) {
    for (String queue : settings.queueNames()) {
      sqs.getQueueUrl(
          software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest.builder()
              .queueName(queue)
              .build());
      System.out.println("SQS 큐 확인: " + queue);
    }
    s3.headBucket(
        software.amazon.awssdk.services.s3.model.HeadBucketRequest.builder()
            .bucket(settings.bucketName())
            .build());
    System.out.println("S3 버킷 확인 완료");
  }
}
