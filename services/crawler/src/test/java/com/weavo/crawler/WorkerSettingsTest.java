package com.weavo.crawler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WorkerSettingsTest {
  private WorkerSettings settings(String environment, String endpoint, String bucket) {
    return new WorkerSettings(environment, "ap-northeast-2", endpoint, bucket, "", "", "");
  }

  @Test
  void localRequiresEndpoint() {
    assertThatThrownBy(() -> settings("local", "", "").validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("WEAVO_AWS_ENDPOINT_URL");
  }

  @Test
  void remoteRejectsEndpoint() {
    assertThatThrownBy(() -> settings("dev", "http://localhost:4566", "bucket").validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("endpoint");
  }

  @Test
  void remoteRequiresResources() {
    assertThatThrownBy(() -> settings("dev", "", "").validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("WEAVO_S3_BUCKET");
  }

  @Test
  void localUsesExpectedResources() {
    var settings = settings("local", "http://localhost:4566", "");
    settings.validate();
    assertThat(settings.bucketName()).isEqualTo("weavo-local-artifacts");
    assertThat(settings.queueNames())
        .containsExactly(
            "collection-priority-jobs", "collection-scheduled-jobs", "control-results");
  }

  @Test
  void rejectsUnknownEnvironment() {
    assertThatThrownBy(() -> settings("staging", "", "").validate())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("WEAVO_ENV");
  }
}
