package com.weavo.crawler.collection.application;

import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionCompleted;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionExecute;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionProjectResult;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.MessageError;
import com.weavo.worker.application.AttemptContext;
import com.weavo.worker.application.JobProcessor.Job;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Routes an accepted collection attempt to its execution-reason handler. */
public final class CollectionJobHandlers {
  private final Map<ExecutionReason, Handler> handlers;

  public CollectionJobHandlers(Map<ExecutionReason, Handler> handlers) {
    this.handlers = Map.copyOf(handlers);
  }

  public CollectionCompleted execute(Job<CollectionExecute> job, AttemptContext attempt)
      throws InterruptedException {
    return execute(job, attempt, (project, fields, error) -> {});
  }

  public CollectionCompleted execute(
      Job<CollectionExecute> job, AttemptContext attempt, IssueReporter reporter)
      throws InterruptedException {
    var reason = ExecutionReason.valueOf(job.payload().executionReason());
    var handler = handlers.get(reason);
    if (handler == null) {
      throw new IllegalStateException("No collection handler for " + reason);
    }
    return handler.execute(job, attempt, reporter);
  }

  public static CollectionJobHandlers forTesting() {
    return using(CollectionJobHandlers::fixedResult);
  }

  public static CollectionJobHandlers using(Handler handler) {
    Map<ExecutionReason, Handler> handlers = new EnumMap<>(ExecutionReason.class);
    for (ExecutionReason reason : ExecutionReason.values()) {
      handlers.put(reason, handler);
    }
    return new CollectionJobHandlers(handlers);
  }

  private static CollectionCompleted fixedResult(
      Job<CollectionExecute> job, AttemptContext attempt) {
    var request = job.payload();
    var error =
        new MessageError(
            "TECHNICAL_FAILURE", null, "테스트 처리기는 실제 수집을 실행하지 않습니다.", Map.of(), false, null);
    var projects =
        request.projects().stream()
            .map(project ->
                new CollectionProjectResult(
                    project.projectId(),
                    project.projectSourceId(),
                    project.schemaVersion(),
                    project.connectorRevision(),
                    "FAILED",
                    false,
                    null,
                    error))
            .toList();
    return new CollectionCompleted(
        attempt.id().toString(),
        request.sourceId(),
        request.navigationRevision(),
        "FAILED",
        "TECHNICAL_FAILURE",
        attempt.startedAt().toString(),
        attempt.now().toString(),
        null,
        null,
        projects,
        error);
  }

  public enum ExecutionReason {
    INITIAL,
    SCHEDULED,
    MANUAL,
    REVISION,
    RECOVERY
  }

  @FunctionalInterface
  public interface Handler {
    CollectionCompleted execute(Job<CollectionExecute> job, AttemptContext attempt)
        throws InterruptedException;

    default CollectionCompleted execute(
        Job<CollectionExecute> job, AttemptContext attempt, IssueReporter reporter)
        throws InterruptedException {
      return execute(job, attempt);
    }
  }

  @FunctionalInterface
  public interface IssueReporter {
    void report(String projectSourceId, List<String> fields, MessageError error)
        throws InterruptedException;
  }
}
