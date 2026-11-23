package com.weavo.crawler.collection.infrastructure.messaging;

import com.weavo.crawler.collection.application.CollectionJobHandlers;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionExecute;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.JobIssueDetected;
import com.weavo.worker.application.AttemptContext;
import com.weavo.worker.application.JobProcessor;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
    var notices = new ArrayList<String>();
    var payload =
        handlers.execute(
            job,
            attempt,
            (id, fields, error) -> {
              String notice = issueNotice(job, attempt, id, fields, error);
              if (!attempt.publishNotice(notice)) {
                notices.add(notice);
              }
            });
    var event =
        new EventEnvelope<>(
            1,
            "collection.completed",
            UUID.randomUUID().toString(),
            job.id().toString(),
            attempt.now().toString(),
            payload);
    String key = "collections/%s/attempts/%s/result.json".formatted(job.id(), attempt.id());
    return new Result(key, contract.writeEvent(event), notices);
  }

  private String issueNotice(
      Job<CollectionExecute> job,
      AttemptContext attempt,
      String projectSourceId,
      List<String> fields,
      Messages.MessageError error) {
    var command = job.payload();
    var project =
        projectSourceId == null
            ? null
            : command.projects().stream()
                .filter(target -> target.projectSourceId().equals(projectSourceId))
                .findFirst()
                .orElseThrow();
    var issue =
        new JobIssueDetected(
            attempt.id().toString(),
            "CRAWLER",
            project == null ? "SOURCE" : "PROJECT_SOURCE",
            command.sourceId(),
            project == null ? null : project.projectId(),
            projectSourceId,
            fields,
            project == null ? null : project.schemaVersion(),
            project == null ? null : project.connectorRevision(),
            command.navigationRevision(),
            error);
    return contract.writeEvent(
        new EventEnvelope<>(
            1,
            "job.issue.detected",
            UUID.randomUUID().toString(),
            job.id().toString(),
            attempt.now().toString(),
            issue));
  }
}
