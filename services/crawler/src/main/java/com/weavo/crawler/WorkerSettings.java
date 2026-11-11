package com.weavo.crawler;

import java.net.URI;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "weavo")
public record WorkerSettings(
    String environment,
    String region,
    String endpoint,
    String bucket,
    String priorityQueue,
    String scheduledQueue,
    String resultQueue) {
  public void validate() {
    if (!List.of("local", "dev", "prod").contains(environment)) {
      throw new IllegalArgumentException("WEAVO_ENV must be local, dev or prod");
    }
    if (region == null || region.isBlank()) {
      throw new IllegalArgumentException("AWS_REGION is required");
    }
    boolean hasEndpoint = endpoint != null && !endpoint.isBlank();
    if (isLocal() && !hasEndpoint) {
      throw new IllegalArgumentException("로컬 환경에는 WEAVO_AWS_ENDPOINT_URL이 필요합니다.");
    }
    if (!isLocal() && hasEndpoint) {
      throw new IllegalArgumentException("AWS 환경에서는 로컬 endpoint를 사용할 수 없습니다.");
    }
    if (hasEndpoint) {
      URI uri = URI.create(endpoint);
      if (uri.getHost() == null
          || !("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))) {
        throw new IllegalArgumentException(
            "WEAVO_AWS_ENDPOINT_URL must be an absolute HTTP(S) URL");
      }
    }
    bucketName();
    queueNames();
  }

  public boolean isLocal() {
    return "local".equals(environment);
  }

  public String bucketName() {
    return resourceName(bucket, "weavo-local-artifacts", "WEAVO_S3_BUCKET");
  }

  public List<String> queueNames() {
    return List.of(
        resourceName(priorityQueue, "collection-priority-jobs", "WEAVO_PRIORITY_QUEUE"),
        resourceName(scheduledQueue, "collection-scheduled-jobs", "WEAVO_SCHEDULED_QUEUE"),
        resourceName(resultQueue, "control-results", "WEAVO_RESULT_QUEUE"));
  }

  private String resourceName(String value, String localDefault, String variable) {
    if (value != null && !value.isBlank()) return value.trim();
    if (isLocal()) return localDefault;
    throw new IllegalArgumentException(variable + " 환경 변수가 필요합니다.");
  }
}
