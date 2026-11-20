package com.weavo.analysis.job.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.weavo.analysis.job.application.AnalysisJobHandlers;
import com.weavo.worker.application.AttemptContext;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AnalysisJobProcessorTest {
  @Test
  void publishesAContractValidFixedResultWithTheOriginalJobAndAttempt() throws Exception {
    var contract = new ContractJson();
    var processor = new AnalysisJobProcessor(contract, AnalysisJobHandlers.forTesting());
    var job = processor.read(fixture());
    var attempt = mock(AttemptContext.class);
    UUID attemptId = UUID.randomUUID();
    when(attempt.id()).thenReturn(attemptId);
    when(attempt.now()).thenReturn(Instant.parse("2026-11-20T10:00:01Z"));

    var result = processor.execute(job, attempt);
    var event = contract.readEvent(result.body());
    var payload = contract.eventPayload(event, Messages.AnalysisCompleted.class);

    assertThat(event.eventType()).isEqualTo("analysis.completed");
    assertThat(event.jobId()).isEqualTo(job.id().toString());
    assertThat(payload.attemptId()).isEqualTo(attemptId.toString());
    assertThat(payload.status()).isEqualTo("FAILED");
    assertThat(payload.analysisId()).isEqualTo(job.payload().analysisId());
    assertThat(payload.revision()).isEqualTo(job.payload().revision());
    assertThat(payload.candidate()).isNull();
    assertThat(result.objectKey())
        .isEqualTo(
            "analysis/%s/jobs/%s/attempts/%s/result.json"
                .formatted(job.payload().analysisId(), job.id(), attemptId));
  }

  private static String fixture() throws IOException {
    try (var stream =
        AnalysisJobProcessorTest.class.getResourceAsStream("/contracts/analysis.execute.json")) {
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
