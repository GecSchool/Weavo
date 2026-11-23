package com.weavo.siteaccess;

import static com.weavo.siteaccess.SiteAccessException.Kind.CANCELLED;
import static com.weavo.siteaccess.SiteAccessException.Kind.WORK_LIMIT;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** A worker supplies its deadline, cancellation signal and permission to start another request. */
public final class WorkBudget {
  @FunctionalInterface
  public interface WorkPermission {
    /** Wait for authorization only until the supplied operation deadline. */
    void check(Instant deadline) throws InterruptedException, TimeoutException;
  }

  private final Instant deadline;
  private final BooleanSupplier cancelled;
  private final WorkPermission permission;
  private final SiteAccessException.Kind expiry;

  public WorkBudget(Instant deadline, BooleanSupplier cancelled, WorkPermission permission) {
    this(deadline, cancelled, permission, WORK_LIMIT);
  }

  private WorkBudget(
      Instant deadline,
      BooleanSupplier cancelled,
      WorkPermission permission,
      SiteAccessException.Kind expiry) {
    this.deadline = Objects.requireNonNull(deadline);
    this.cancelled = Objects.requireNonNull(cancelled);
    this.permission = Objects.requireNonNull(permission);
    this.expiry = expiry;
  }

  public void check() {
    checkCancellation();
    if (!Instant.now().isBefore(deadline)) {
      throw new SiteAccessException(expiry, "Site access deadline expired");
    }
  }

  public Duration remaining() {
    check();
    return remainingUntilDeadline();
  }

  /** Lets an in-flight operation classify expiry while still observing cancellation. */
  public Duration remainingWhileWaiting() {
    checkCancellation();
    return remainingUntilDeadline();
  }

  private Duration remainingUntilDeadline() {
    Duration remaining = Duration.between(Instant.now(), deadline);
    return remaining.isNegative() ? Duration.ZERO : remaining;
  }

  private void checkCancellation() {
    if (Thread.currentThread().isInterrupted() || cancelled.getAsBoolean()) {
      throw new SiteAccessException(CANCELLED, "Site access was cancelled");
    }
  }

  /** Checks the budget and asks the worker to confirm permission for a new request. */
  public void beforeRequest() {
    check();
    try {
      permission.check(deadline);
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new SiteAccessException(CANCELLED, "Site access was interrupted", error);
    } catch (CancellationException error) {
      throw new SiteAccessException(CANCELLED, "Site access was cancelled", error);
    } catch (TimeoutException error) {
      throw new SiteAccessException(expiry, "Site access deadline expired", error);
    }
    check();
  }

  WorkBudget cap(Duration duration, SiteAccessException.Kind timeout) {
    Instant capped = Instant.now().plus(duration);
    return capped.isBefore(deadline)
        ? new WorkBudget(capped, cancelled, permission, timeout)
        : this;
  }

  long timeoutMillis() {
    return Math.max(1, remaining().toMillis());
  }

  void pause(Duration delay) {
    long resumeAt = System.nanoTime() + delay.toNanos();
    long nanos;
    while ((nanos = resumeAt - System.nanoTime()) > 0) {
      long waitMillis = Math.min(50, timeoutMillis());
      try {
        TimeUnit.NANOSECONDS.sleep(Math.min(nanos, TimeUnit.MILLISECONDS.toNanos(waitMillis)));
      } catch (InterruptedException error) {
        Thread.currentThread().interrupt();
        throw new SiteAccessException(CANCELLED, "Site access was interrupted", error);
      }
    }
    check();
  }
}
