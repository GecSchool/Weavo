package com.weavo.worker.application;

import java.time.Duration;
import java.util.Objects;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("weavo.execution")
public record WorkerTiming(
    @DefaultValue("5s") Duration heartbeatInterval,
    @DefaultValue("30s") Duration visibility,
    @DefaultValue("30s") Duration completionGrace,
    @DefaultValue("3s") Duration controlTimeout,
    @DefaultValue("15s") Duration awsTimeout) {

  public WorkerTiming {
    requirePositive(heartbeatInterval);
    requirePositive(completionGrace);
    requirePositive(controlTimeout);
    requirePositive(awsTimeout);
    if (visibility == null
        || visibility.getNano() != 0
        || visibility.compareTo(heartbeatInterval) <= 0
        || visibility.toSeconds() > 43_200) {
      throw new IllegalArgumentException(
          "Worker visibility must be a whole number of seconds above the heartbeat interval");
    }
    if (awsTimeout.compareTo(Duration.ofSeconds(5)) <= 0) {
      throw new IllegalArgumentException("Worker AWS timeout must allow the 5-second long poll");
    }
  }

  private static void requirePositive(Duration duration) {
    if (Objects.requireNonNull(duration).toMillis() <= 0) {
      throw new IllegalArgumentException("Worker durations must be at least one millisecond");
    }
  }
}
