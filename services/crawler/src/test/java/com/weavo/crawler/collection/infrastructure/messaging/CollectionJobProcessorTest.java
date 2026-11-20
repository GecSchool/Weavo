package com.weavo.crawler.collection.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.weavo.crawler.collection.application.CollectionJobHandlers;
import com.weavo.worker.application.AttemptContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CollectionJobProcessorTest {
  @Test
  void publishesAContractValidFixedResultWithTheOriginalJobAndAttempt() throws Exception {
    var contract = new ContractJson();
    var processor = new CollectionJobProcessor(contract, CollectionJobHandlers.forTesting());
    var job = processor.read(fixture());
    var attempt = mock(AttemptContext.class);
    UUID attemptId = UUID.randomUUID();
    when(attempt.id()).thenReturn(attemptId);
    when(attempt.now()).thenReturn(Instant.parse("2026-11-20T10:00:01Z"));
    when(attempt.startedAt()).thenReturn(Instant.parse("2026-11-20T10:00:00Z"));

    var result = processor.execute(job, attempt);
    var event = contract.readEvent(result.body());
    var payload = contract.eventPayload(event, Messages.CollectionCompleted.class);

    assertThat(event.eventType()).isEqualTo("collection.completed");
    assertThat(event.jobId()).isEqualTo(job.id().toString());
    assertThat(payload.attemptId()).isEqualTo(attemptId.toString());
    assertThat(payload.status()).isEqualTo("FAILED");
    assertThat(payload.sourceId()).isEqualTo(job.payload().sourceId());
    assertThat(payload.navigationRevision()).isEqualTo(job.payload().navigationRevision());
    assertThat(payload.snapshotRef()).isNull();
    assertThat(payload.projectResults())
        .allSatisfy(project -> assertThat(project.canIngest()).isFalse());
    assertThat(result.objectKey())
        .isEqualTo("collections/%s/attempts/%s/result.json".formatted(job.id(), attemptId));
  }

  private static String fixture() throws IOException {
    try (var stream =
        CollectionJobProcessorTest.class.getResourceAsStream(
            "/contracts/collection.execute.json")) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
