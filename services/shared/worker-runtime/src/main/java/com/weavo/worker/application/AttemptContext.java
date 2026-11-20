package com.weavo.worker.application;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Deadlines and the cooperative cancellation signal used by a running handler. */
public final class AttemptContext {
  private final Clock clock;
  private final UUID id;
  private final Instant startedAt;
  private final Instant workDeadline;
  private final Instant executionDeadline;
  private Instant leaseExpiresAt;
  private boolean awaitingConfirmation;
  private boolean cancelled;

  AttemptContext(
      Clock clock, UUID id, Duration workBudget, Duration completionGrace, Instant leaseExpiresAt) {
    this.clock = clock;
    this.id = id;
    this.startedAt = clock.instant();
    this.workDeadline = startedAt.plus(workBudget);
    this.executionDeadline = workDeadline.plus(completionGrace);
    this.leaseExpiresAt = leaseExpiresAt;
  }

  public UUID id() {
    return id;
  }

  public Instant startedAt() {
    return startedAt;
  }

  public Instant workDeadline() {
    return workDeadline;
  }

  public Instant now() {
    return clock.instant();
  }

  /** Call before each new site request or other budgeted operation. */
  public void requireWorkAllowed() throws InterruptedException, TimeoutException {
    requireOwnership();
    if (!now().isBefore(workDeadline)) {
      throw new TimeoutException("Worker action budget expired");
    }
  }

  /** Call before writing objects or publishing a result. */
  public synchronized void requireOwnership() throws InterruptedException {
    while (awaitingConfirmation && isValid()) {
      long remainingNanos =
          Duration.between(
                  now(),
                  leaseExpiresAt.isBefore(executionDeadline) ? leaseExpiresAt : executionDeadline)
              .toNanos();
      if (remainingNanos > 0) {
        TimeUnit.NANOSECONDS.timedWait(this, remainingNanos);
      }
    }
    if (!isValid()) {
      throw new CancellationException("Attempt lease or execution deadline is no longer valid");
    }
  }

  public synchronized boolean isCancellationRequested() {
    return !isValid();
  }

  synchronized void pauseForRecheck() {
    awaitingConfirmation = true;
  }

  synchronized void renew(Instant expiresAt) {
    // Once locally expired or cancelled, an already running handler must stay stopped.
    if (!isValid() || !now().isBefore(expiresAt)) {
      cancel();
      return;
    }
    leaseExpiresAt = expiresAt;
    awaitingConfirmation = false;
    notifyAll();
  }

  synchronized void cancel() {
    cancelled = true;
    notifyAll();
  }

  private boolean isValid() {
    Instant now = now();
    return !cancelled && now.isBefore(leaseExpiresAt) && now.isBefore(executionDeadline);
  }
}
