package com.weavo.crawler.collection.infrastructure.messaging;

import com.weavo.crawler.collection.application.CollectionJobHandlers;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionExecute;
import com.weavo.worker.application.AttemptContext;
import com.weavo.worker.application.JobProcessor;
import java.time.Duration;
import java.util.UUID;

/** Translates validated collection messages at the worker boundary. */
public final class CollectionJobProcessor implements JobProcessor<CollectionExecute> {
  private final ContractJson contract;
  private final CollectionJobHandlers handlers;

  public CollectionJobProcessor(ContractJson contract, CollectionJobHandlers handlers) {
    this.contract = contract;
    this.handlers = handlers;
  }

  @Override
  public Job<CollectionExecute> read(String body) {
    var command = contract.readCommand(body);
    var request = contract.commandPayload(command, CollectionExecute.class);
    return new Job<>(
        UUID.fromString(command.jobId()),
        command.expiresAt(),
        Duration.ofSeconds(request.maxDurationSeconds()),
        request);
  }

  @Override
  public Result execute(Job<CollectionExecute> job, AttemptContext attempt)
      throws InterruptedException {
    var payload = handlers.execute(job.payload(), attempt);
    var event =
        new EventEnvelope<>(
            1,
            "collection.completed",
            UUID.randomUUID().toString(),
            job.id().toString(),
            attempt.now().toString(),
            payload);
    String key = "collections/%s/attempts/%s/result.json".formatted(job.id(), attempt.id());
    return new Result(key, contract.writeEvent(event));
  }
}
