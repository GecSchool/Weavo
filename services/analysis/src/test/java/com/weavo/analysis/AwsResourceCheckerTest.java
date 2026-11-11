package com.weavo.analysis;

import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;

class AwsResourceCheckerTest {
  @Test
  void onlyChecksQueuesAndBucketWithoutReceivingMessages() {
    var settings =
        new WorkerSettings("local", "ap-northeast-2", "http://localhost:4566", "", "", "");
    var sqs = mock(SqsClient.class);
    var s3 = mock(S3Client.class);
    new AwsResourceChecker(settings).checkResources(sqs, s3);
    for (String queue : new String[] {"analysis-jobs", "control-results"}) {
      verify(sqs).getQueueUrl(GetQueueUrlRequest.builder().queueName(queue).build());
    }
    verify(s3).headBucket(HeadBucketRequest.builder().bucket("weavo-local-artifacts").build());
    verifyNoMoreInteractions(sqs, s3);
  }
}
