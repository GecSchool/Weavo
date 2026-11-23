package com.weavo.analysis.joint.application;

import com.weavo.analysis.job.infrastructure.messaging.Messages.ObjectReference;
import com.weavo.worker.application.AttemptContext;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.json.JsonMapper;

/** Stores Analysis artifacts under paths owned by the job attempt. */
public final class AnalysisArtifacts {
  private final String bucket;
  private final Read read;
  private final Write write;
  private final JsonMapper json =
      JsonMapper.builder().propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE).build();

  public AnalysisArtifacts(String bucket, Read read, Write write) {
    this.bucket = bucket;
    this.read = read;
    this.write = write;
  }

  public ObjectReference put(String key, Object body, AttemptContext attempt)
      throws InterruptedException {
    attempt.requireOwnership();
    write.put(key, "application/json", json.writeValueAsBytes(body));
    attempt.requireOwnership();
    return new ObjectReference(bucket, key);
  }

  public <T> T get(ObjectReference ref, Class<T> type, AttemptContext attempt)
      throws InterruptedException {
    if (ref == null || !bucket.equals(ref.bucket())) {
      throw new IllegalArgumentException("Analysis reference belongs to a different bucket");
    }
    try {
      attempt.requireWorkAllowed();
    } catch (java.util.concurrent.TimeoutException timeout) {
      throw new com.weavo.siteaccess.SiteAccessException(
          com.weavo.siteaccess.SiteAccessException.Kind.WORK_LIMIT, "Analysis artifact deadline");
    }
    return json.readValue(read.get(ref.key()), type);
  }

  public static String attemptPath(String jobId, AttemptContext attempt) {
    return "/" + jobId + "/" + attempt.id();
  }

  @FunctionalInterface
  public interface Read {
    byte[] get(String key);
  }

  @FunctionalInterface
  public interface Write {
    void put(String key, String contentType, byte[] body);
  }
}
