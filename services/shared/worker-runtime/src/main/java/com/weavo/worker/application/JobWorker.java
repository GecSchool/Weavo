package com.weavo.worker.application;

import com.weavo.worker.application.JobProcessor.Job;
import com.weavo.worker.infrastructure.AwsJobTransport;
import com.weavo.worker.infrastructure.AwsJobTransport.Delivery;
import com.weavo.worker.infrastructure.ControlAttemptClient;
import com.weavo.worker.infrastructure.ControlAttemptClient.Claim;
import com.weavo.worker.infrastructure.ControlAttemptClient.ControlFailure;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.core.exception.SdkException;

/** One polling slot per worker instance. Control arbitrates ownership across instances. */
public final class JobWorker<P> implements AutoCloseable {
  private static final Logger LOGGER = LoggerFactory.getLogger(JobWorker.class);

  private final JobProcessor<P> processor;
  private final AwsJobTransport transport;
  private final ControlAttemptClient control;
  private final WorkerTiming timing;
  private final Clock clock;
  private final ExecutorService polling =
      Executors.newSingleThreadExecutor(Thread.ofPlatform().name("worker-poll").factory());
  private final ScheduledExecutorService maintenance =
      Executors.newSingleThreadScheduledExecutor(
          Thread.ofPlatform().name("worker-lease").factory());

  private volatile AttemptContext active;
  private volatile boolean closed;
  private boolean started;

  public JobWorker(
      JobProcessor<P> processor,
      AwsJobTransport transport,
      ControlAttemptClient control,
      WorkerTiming timing,
      Clock clock) {
    this.processor = processor;
    this.transport = transport;
    this.control = control;
    this.timing = timing;
    this.clock = clock;
  }

  public synchronized void start() {
    if (started || closed) {
      return;
    }
    transport.prepare();
    started = true;
    polling.execute(this::poll);
  }

  private void poll() {
    while (!closed && !Thread.currentThread().isInterrupted()) {
      try {
        var delivery = transport.receive(visibilitySeconds());
        if (delivery != null && !closed) {
          handle(delivery);
        }
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
      } catch (RuntimeException exception) {
        if (closed) {
          return;
        }
        LOGGER.warn("Worker poll failed errorType={}", exception.getClass().getSimpleName());
        // Back off from an unavailable queue instead of busy-looping.
        try {
          Thread.sleep(Duration.ofSeconds(1));
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  void handle(Delivery delivery) throws InterruptedException {
    UUID jobId = null;
    try {
      Job<P> job = processor.read(delivery.body());
      jobId = job.id();
      if (closed) {
        return;
      }
      UUID attemptId = UUID.randomUUID();
      // Control's DB decides the command's expires_at using its own clock.
      Claim claim = claim(jobId, attemptId);
      if (closed) {
        return;
      }
      switch (claim.status()) {
        case TERMINAL, EXPIRED -> transport.delete(delivery);
        case BUSY -> transport.changeVisibility(delivery, busyDelay(claim.leaseExpiresAt()));
        case ACQUIRED -> execute(job, delivery, attemptId, claim.leaseExpiresAt());
      }
    } catch (CancellationException exception) {
      LOGGER.info("Worker attempt stopped jobId={}", jobId);
    } catch (ControlFailure exception) {
      LOGGER.warn(
          "Worker could not confirm Control status jobId={} reason={}",
          jobId,
          exception.getMessage());
    } catch (RuntimeException exception) {
      // Failure leaves this receipt available to SQS redelivery and the Control job ledger.
      LOGGER.warn(
          "Worker delivery left unacknowledged jobId={} errorType={}",
          jobId,
          exception.getClass().getSimpleName());
    }
  }

  private Claim claim(UUID jobId, UUID attemptId) throws InterruptedException {
    try {
      return control.claim(jobId, attemptId);
    } catch (ControlFailure failure) {
      if (closed || !failure.retryable()) {
        throw failure;
      }
      return control.claim(jobId, attemptId);
    }
  }

  private void execute(Job<P> job, Delivery delivery, UUID attemptId, Instant initialLease)
      throws InterruptedException {
    var attempt =
        new AttemptContext(
            clock, attemptId, job.workBudget(), timing.completionGrace(), initialLease);
    active = attempt;
    try {
      if (closed) {
        return;
      }
      attempt.requireOwnership();
      transport.changeVisibility(delivery, visibilitySeconds());
      long periodMillis = timing.heartbeatInterval().toMillis();
      var heartbeat =
          maintenance.scheduleWithFixedDelay(
              () -> maintainLease(job.id(), delivery, attempt),
              periodMillis,
              periodMillis,
              TimeUnit.MILLISECONDS);
      try {
        attempt.requireOwnership();
        transport.recordExecutionStart(delivery);
        var result = processor.execute(job, attempt);
        withTransportRetry(attempt, () -> transport.store(result.objectKey(), result.body()));
        for (String notice : result.notices()) {
          withTransportRetry(attempt, () -> transport.publish(notice));
        }
        withTransportRetry(attempt, () -> transport.publish(result.body()));
        transport.delete(delivery);
      } finally {
        heartbeat.cancel(true);
      }
    } finally {
      attempt.cancel();
      active = null;
    }
  }

  private void maintainLease(UUID jobId, Delivery delivery, AttemptContext attempt) {
    try {
      attempt.requireOwnership();
      Instant lease;
      try {
        lease = control.heartbeat(jobId, attempt.id());
      } catch (ControlFailure failure) {
        attempt.pauseForRecheck();
        if (!failure.retryable() || attempt.isCancellationRequested()) {
          throw failure;
        }
        lease = control.heartbeat(jobId, attempt.id());
      }
      if (lease == null) {
        attempt.cancel();
        LOGGER.info("Control revoked the worker lease jobId={} attemptId={}", jobId, attempt.id());
        return;
      }
      attempt.renew(lease);
      attempt.requireOwnership();
      transport.changeVisibility(delivery, visibilitySeconds());
    } catch (InterruptedException exception) {
      attempt.cancel();
      Thread.currentThread().interrupt();
    } catch (RuntimeException exception) {
      attempt.cancel();
      LOGGER.warn(
          "Worker lease maintenance stopped jobId={} attemptId={} errorType={}",
          jobId,
          attempt.id(),
          exception.getClass().getSimpleName());
    }
  }

  private static void withTransportRetry(AttemptContext attempt, Runnable operation)
      throws InterruptedException {
    attempt.requireOwnership();
    try {
      operation.run();
    } catch (SdkException exception) {
      boolean retryable = exception instanceof SdkClientException;
      if (exception instanceof AwsServiceException serviceError) {
        retryable =
            serviceError.statusCode() >= 500
                || serviceError.isThrottlingException()
                || serviceError.statusCode() == 409;
      }
      if (!retryable) {
        throw exception;
      }
      attempt.requireOwnership();
      operation.run();
    }
  }

  private int busyDelay(Instant leaseExpiresAt) {
    long seconds = Duration.between(clock.instant(), leaseExpiresAt).toSeconds() + 1;
    return (int) Math.max(1, Math.min(43_200, seconds));
  }

  private int visibilitySeconds() {
    return Math.toIntExact(timing.visibility().toSeconds());
  }

  @Override
  public synchronized void close() {
    if (closed) {
      return;
    }
    closed = true;
    var current = active;
    if (current != null) {
      current.cancel();
    }
    polling.shutdownNow();
    maintenance.shutdownNow();
    try {
      polling.awaitTermination(5, TimeUnit.SECONDS);
      maintenance.awaitTermination(5, TimeUnit.SECONDS);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
    } finally {
      try {
        transport.close();
      } finally {
        control.close();
      }
    }
  }
}
