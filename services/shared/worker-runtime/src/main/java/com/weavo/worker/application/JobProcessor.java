package com.weavo.worker.application;

import java.time.Duration;
import java.util.UUID;

/** Interprets service-specific commands and prepares the complete result for one attempt. */
public interface JobProcessor<P> {
  Job<P> read(String body);

  Result execute(Job<P> job, AttemptContext attempt) throws InterruptedException;

  record Job<P>(UUID id, String expiresAt, Duration workBudget, P payload) {}

  record Result(String objectKey, String body) {}
}
