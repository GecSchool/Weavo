package com.weavo.worker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.weavo.worker.application.JobProcessor.Job;
import com.weavo.worker.application.JobProcessor.Result;
import com.weavo.worker.infrastructure.AwsJobTransport;
import com.weavo.worker.infrastructure.AwsJobTransport.Delivery;
import com.weavo.worker.infrastructure.ControlAttemptClient;
import com.weavo.worker.infrastructure.ControlAttemptClient.Claim;
import com.weavo.worker.infrastructure.ControlAttemptClient.ControlFailure;
import com.weavo.worker.infrastructure.ControlAttemptClient.Status;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import software.amazon.awssdk.core.exception.SdkClientException;

class JobWorkerTest {
  private static final Instant NOW = Instant.parse("2026-11-20T00:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final String RESULT = "{\"event_id\":\"fixed-for-this-attempt\"}";
  private static final String KEY = "attempts/test/result.json";
  private static final UUID JOB_ID = UUID.randomUUID();
  private static final Job<String> JOB =
      new Job<>(JOB_ID, NOW.plusSeconds(30).toString(), Duration.ofSeconds(60), "job");
  private static final Delivery FIRST = new Delivery("queue", "receipt-1", "command");
  private static final Delivery DUPLICATE = new Delivery("queue", "receipt-2", "command");
  private static final Claim ACQUIRED = new Claim(Status.ACQUIRED, NOW.plusSeconds(30));
  private static final WorkerTiming TIMING =
      new WorkerTiming(
          Duration.ofSeconds(2),
          Duration.ofSeconds(30),
          Duration.ofSeconds(15),
          Duration.ofSeconds(1),
          Duration.ofSeconds(15));

  private final AwsJobTransport transport = mock(AwsJobTransport.class);
  private final ControlAttemptClient control = mock(ControlAttemptClient.class);
  private final AtomicInteger executed = new AtomicInteger();
  private final AtomicReference<AttemptContext> execution = new AtomicReference<>();

  @Test
  void storesAndPublishesAnAcquiredResultBeforeAcknowledgingIt() throws InterruptedException {
    when(control.claim(eq(JOB_ID), any())).thenReturn(ACQUIRED);

    try (var worker = worker(TIMING, attempt -> new Result(KEY, RESULT))) {
      worker.handle(FIRST);
      assertThat(executed.get()).isEqualTo(1);
      var attemptIds = ArgumentCaptor.forClass(UUID.class);
      verify(control).claim(eq(JOB_ID), attemptIds.capture());
      assertThat(execution.get().id()).isEqualTo(attemptIds.getValue());
      var ordered = inOrder(transport);
      ordered.verify(transport).changeVisibility(FIRST, 30);
      ordered.verify(transport).store(KEY, RESULT);
      ordered.verify(transport).publish(RESULT);
      ordered.verify(transport).delete(FIRST);
    }
  }

  @Test
  void aBusyDuplicateBacksOffOnlyItsReceipt() throws InterruptedException {
    when(control.claim(eq(JOB_ID), any()))
        .thenReturn(ACQUIRED, new Claim(Status.BUSY, NOW.plusSeconds(10)));

    try (var worker = worker(TIMING, attempt -> new Result(KEY, RESULT))) {
      worker.handle(FIRST);
      worker.handle(DUPLICATE);

      assertThat(executed.get()).isEqualTo(1);
      verify(transport).changeVisibility(DUPLICATE, 11);
      verify(transport, never()).delete(DUPLICATE);
      var attemptIds = ArgumentCaptor.forClass(UUID.class);
      verify(control, times(2)).claim(eq(JOB_ID), attemptIds.capture());
      assertThat(attemptIds.getAllValues()).doesNotHaveDuplicates();
    }
  }

  @ParameterizedTest
  @EnumSource(
      value = Status.class,
      names = {"TERMINAL", "EXPIRED"})
  void acknowledgesTerminalAndExpiredDeliveriesWithoutExecuting(Status status)
      throws InterruptedException {
    when(control.claim(eq(JOB_ID), any())).thenReturn(new Claim(status, null));

    try (var worker = worker(TIMING, attempt -> new Result(KEY, RESULT))) {
      worker.handle(FIRST);

      assertThat(executed.get()).isZero();
      verify(transport).delete(FIRST);
    }
  }

  @Test
  void invalidCommandsAreNotClaimedOrAcknowledged() throws InterruptedException {
    var processor =
        new JobProcessor<String>() {
          @Override
          public Job<String> read(String body) {
            throw new IllegalArgumentException("Invalid command");
          }

          @Override
          public Result execute(Job<String> job, AttemptContext attempt) {
            throw new AssertionError("Invalid command was executed");
          }
        };
    try (var worker = new JobWorker<>(processor, transport, control, TIMING, CLOCK)) {
      worker.handle(FIRST);

      verifyNoInteractions(control, transport);
    }
  }

  @Test
  void leavesTheReceiptWhenServiceAuthenticationFails() throws InterruptedException {
    when(control.claim(eq(JOB_ID), any())).thenThrow(new ControlFailure("HTTP 401", false));

    try (var worker = worker(TIMING, attempt -> new Result(KEY, RESULT))) {
      worker.handle(FIRST);

      assertThat(executed.get()).isZero();
      verify(control).claim(eq(JOB_ID), any());
      verifyNoInteractions(transport);
    }
  }

  @Test
  void retriesAnUncertainClaimWithTheSameAttemptId() throws InterruptedException {
    when(control.claim(eq(JOB_ID), any()))
        .thenThrow(new ControlFailure("HTTP 503", true))
        .thenReturn(ACQUIRED);

    try (var worker = worker(TIMING, attempt -> new Result(KEY, RESULT))) {
      worker.handle(FIRST);

      var attemptIds = ArgumentCaptor.forClass(UUID.class);
      verify(control, times(2)).claim(eq(JOB_ID), attemptIds.capture());
      assertThat(attemptIds.getAllValues()).containsOnly(execution.get().id());
      verify(transport).delete(FIRST);
    }
  }

  @Test
  void resendsExactlyThePersistedBodyAfterAnAmbiguousSend() throws InterruptedException {
    when(control.claim(eq(JOB_ID), any())).thenReturn(ACQUIRED);
    doThrow(SdkClientException.create("send timeout")).doNothing().when(transport).publish(RESULT);

    try (var worker = worker(TIMING, attempt -> new Result(KEY, RESULT))) {
      worker.handle(FIRST);

      assertThat(executed.get()).isEqualTo(1);
      var ordered = inOrder(transport);
      ordered.verify(transport).store(KEY, RESULT);
      ordered.verify(transport, times(2)).publish(RESULT);
      ordered.verify(transport).delete(FIRST);
    }
  }

  @Test
  void leavesTheReceiptWhenResultStorageFails() throws InterruptedException {
    when(control.claim(eq(JOB_ID), any())).thenReturn(ACQUIRED);
    doThrow(SdkClientException.create("storage timeout")).when(transport).store(KEY, RESULT);

    try (var worker = worker(TIMING, attempt -> new Result(KEY, RESULT))) {
      worker.handle(FIRST);

      verify(transport, never()).publish(any());
      verify(transport, never()).delete(any());
    }
  }

  @Test
  void leavesTheReceiptWhenResultPublicationNeverSucceeds() throws InterruptedException {
    when(control.claim(eq(JOB_ID), any())).thenReturn(ACQUIRED);
    doThrow(SdkClientException.create("send timeout")).when(transport).publish(RESULT);

    try (var worker = worker(TIMING, attempt -> new Result(KEY, RESULT))) {
      worker.handle(FIRST);

      verify(transport, times(2)).publish(RESULT);
      verify(transport, never()).delete(any());
    }
  }

  @Test
  void doesNotResendAfterLosingALeaseDuringTheFirstPublish() throws InterruptedException {
    when(control.claim(eq(JOB_ID), any())).thenReturn(ACQUIRED);
    var publishing = new CountDownLatch(1);
    when(control.heartbeat(eq(JOB_ID), any()))
        .thenAnswer(call -> {
          assertThat(publishing.await(2, TimeUnit.SECONDS)).isTrue();
          return null;
        });
    doAnswer(
            call -> {
              publishing.countDown();
              while (!execution.get().isCancellationRequested()) {
                Thread.sleep(1);
              }
              throw SdkClientException.create("send response lost");
            })
        .when(transport)
        .publish(RESULT);
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          try (var worker = worker(fastHeartbeat(), attempt -> new Result(KEY, RESULT))) {
            worker.handle(FIRST);

            verify(transport).publish(RESULT);
            verify(transport, never()).delete(any());
          }
        });
  }

  @Test
  void aRejectedHeartbeatSignalsTheHandlerAndPreventsPublication() throws InterruptedException {
    when(control.claim(eq(JOB_ID), any())).thenReturn(ACQUIRED);
    when(control.heartbeat(eq(JOB_ID), any())).thenReturn(null);

    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          try (var worker =
              worker(
                  fastHeartbeat(),
                  attempt -> {
                    while (!attempt.isCancellationRequested()) {
                      Thread.sleep(1);
                    }
                    assertThatExceptionOfType(CancellationException.class)
                        .isThrownBy(attempt::requireOwnership);
                    return new Result(KEY, RESULT);
                  })) {
            worker.handle(FIRST);

            assertThat(execution.get().isCancellationRequested()).isTrue();
            verify(control).heartbeat(JOB_ID, execution.get().id());
            verify(transport, never()).store(any(), any());
            verify(transport, never()).publish(any());
            verify(transport, never()).delete(any());
          }
        });
  }

  @Test
  void aLostHeartbeatResponseRechecksTheSameAttemptBeforeResuming() throws InterruptedException {
    when(control.claim(eq(JOB_ID), any())).thenReturn(ACQUIRED);
    var recheckStarted = new CountDownLatch(1);
    var checked = new CountDownLatch(1);
    when(control.heartbeat(eq(JOB_ID), any()))
        .thenThrow(new ControlFailure("response lost", true))
        .thenAnswer(call -> {
          recheckStarted.countDown();
          assertThat(checked.await(2, TimeUnit.SECONDS)).isTrue();
          return NOW.plusSeconds(40);
        });
    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          try (var worker =
              worker(
                  fastHeartbeat(),
                  attempt -> {
                    assertThat(recheckStarted.await(2, TimeUnit.SECONDS)).isTrue();
                    verify(transport, never()).publish(any());
                    checked.countDown();
                    attempt.requireOwnership();
                    return new Result(KEY, RESULT);
                  })) {
            worker.handle(FIRST);

            verify(control, org.mockito.Mockito.atLeast(2)).heartbeat(JOB_ID, execution.get().id());
            verify(transport).publish(RESULT);
            verify(transport).delete(FIRST);
          }
        });
  }

  @Test
  void unrecoverableHeartbeatUncertaintyLeavesTheReceipt() throws InterruptedException {
    when(control.claim(eq(JOB_ID), any())).thenReturn(ACQUIRED);
    when(control.heartbeat(eq(JOB_ID), any())).thenThrow(new ControlFailure("response lost", true));

    assertTimeoutPreemptively(
        Duration.ofSeconds(5),
        () -> {
          try (var worker =
              worker(
                  fastHeartbeat(),
                  attempt -> {
                    while (!attempt.isCancellationRequested()) {
                      Thread.sleep(1);
                    }
                    return new Result(KEY, RESULT);
                  })) {
            worker.handle(FIRST);

            verify(control, times(2)).heartbeat(JOB_ID, execution.get().id());
            verify(transport, never()).publish(any());
            verify(transport, never()).delete(any());
          }
        });
  }

  @Test
  void continuesPollingAnEmptyQueueAndStopsOnClose() throws InterruptedException {
    var waiting = new CountDownLatch(1);
    var released = new CountDownLatch(1);
    when(transport.receive(anyInt()))
        .thenReturn(null)
        .thenAnswer(call -> {
          waiting.countDown();
          try {
            Thread.sleep(Duration.ofSeconds(30));
          } finally {
            released.countDown();
          }
          return null;
        });

    try (var worker = worker(TIMING, attempt -> new Result(KEY, RESULT))) {
      worker.start();
      assertThat(waiting.await(2, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(released.getCount()).isZero();
    verify(transport, times(2)).receive(anyInt());
    verify(transport).close();
    verify(control).close();
  }

  @Test
  void separatesHandlerBudgetFromTheFinalDeliveryDeadline() throws Exception {
    var time = new AtomicReference<>(NOW);
    Clock clock =
        new Clock() {
          @Override
          public Instant instant() {
            return time.get();
          }

          @Override
          public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
          }

          @Override
          public Clock withZone(java.time.ZoneId zone) {
            throw new UnsupportedOperationException("Test clock uses UTC");
          }
        };
    var attempt =
        new AttemptContext(
            clock,
            UUID.randomUUID(),
            Duration.ofSeconds(10),
            Duration.ofSeconds(5),
            NOW.plusSeconds(60));
    time.set(NOW.plusSeconds(10));
    assertThatExceptionOfType(TimeoutException.class).isThrownBy(attempt::requireWorkAllowed);
    attempt.requireOwnership();

    time.set(NOW.plusSeconds(15));
    assertThatExceptionOfType(CancellationException.class).isThrownBy(attempt::requireOwnership);
  }

  @Test
  void stopsWaitingForAuthorizationAtTheOperationDeadline() throws Exception {
    Clock clock = Clock.systemUTC();
    var attempt =
        new AttemptContext(
            clock,
            UUID.randomUUID(),
            Duration.ofSeconds(8),
            Duration.ofSeconds(2),
            clock.instant().plusSeconds(10));
    attempt.pauseForRecheck();

    assertTimeoutPreemptively(
        Duration.ofSeconds(2),
        () ->
            assertThatExceptionOfType(TimeoutException.class)
                .isThrownBy(() -> attempt.requireWorkAllowed(clock.instant().plusMillis(150))));

    assertThat(attempt.isCancellationRequested()).isFalse();
    attempt.renew(clock.instant().plusSeconds(5));
    attempt.requireWorkAllowed(clock.instant().plusSeconds(1));
  }

  private JobWorker<String> worker(WorkerTiming timing, TestHandler handler) {
    var processor =
        new JobProcessor<String>() {
          @Override
          public Job<String> read(String body) {
            return JOB;
          }

          @Override
          public Result execute(Job<String> job, AttemptContext attempt)
              throws InterruptedException {
            execution.set(attempt);
            executed.incrementAndGet();
            return handler.run(attempt);
          }
        };
    return new JobWorker<>(processor, transport, control, timing, CLOCK);
  }

  private static WorkerTiming fastHeartbeat() {
    return new WorkerTiming(
        Duration.ofMillis(15),
        TIMING.visibility(),
        TIMING.completionGrace(),
        TIMING.controlTimeout(),
        TIMING.awsTimeout());
  }

  @FunctionalInterface
  private interface TestHandler {
    Result run(AttemptContext attempt) throws InterruptedException;
  }
}
