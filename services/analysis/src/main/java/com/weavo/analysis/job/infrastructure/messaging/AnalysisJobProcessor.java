package com.weavo.analysis.job.infrastructure.messaging;

import com.weavo.analysis.job.application.AnalysisJobHandlers;
import com.weavo.analysis.job.infrastructure.messaging.Messages.AnalysisExecute;
import com.weavo.worker.application.AttemptContext;
import com.weavo.worker.application.JobProcessor;
import java.time.Duration;
import java.util.UUID;

/** Translates validated analysis messages at the worker boundary. */
public final class AnalysisJobProcessor implements JobProcessor<AnalysisExecute> {
  private final ContractJson contract;
  private final AnalysisJobHandlers handlers;

  public AnalysisJobProcessor(ContractJson contract, AnalysisJobHandlers handlers) {
    this.contract = contract;
    this.handlers = handlers;
  }

  @Override
  public Job<AnalysisExecute> read(String body) {
    var command = contract.readCommand(body);
    var request = contract.commandPayload(command, AnalysisExecute.class);
    return new Job<>(
        UUID.fromString(command.jobId()),
        command.expiresAt(),
        Duration.ofSeconds(request.maxDurationSeconds()),
        request);
  }

  @Override
  public Result execute(Job<AnalysisExecute> job, AttemptContext attempt)
      throws InterruptedException {
    var payload = handlers.execute(job.id().toString(), job.payload(), attempt);
    var event =
        new EventEnvelope<>(
            1,
            "analysis.completed",
            UUID.randomUUID().toString(),
            job.id().toString(),
            attempt.now().toString(),
            payload);
    String key =
        "analysis/%s/jobs/%s/attempts/%s/result.json"
            .formatted(job.payload().analysisId(), job.id(), attempt.id());
    return new Result(key, contract.writeEvent(event));
  }
}
