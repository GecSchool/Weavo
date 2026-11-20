package com.weavo.crawler.collection.application;

import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionCompleted;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionExecute;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionProjectResult;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.MessageError;
import com.weavo.worker.application.AttemptContext;
import java.util.EnumMap;
import java.util.Map;

/** Routes an accepted collection attempt to its execution-reason handler. */
public final class CollectionJobHandlers {
  private final Map<ExecutionReason, Handler> handlers;

  public CollectionJobHandlers(Map<ExecutionReason, Handler> handlers) {
    this.handlers = Map.copyOf(handlers);
  }

  public CollectionCompleted execute(CollectionExecute request, AttemptContext attempt)
      throws InterruptedException {
    var reason = ExecutionReason.valueOf(request.executionReason());
    var handler = handlers.get(reason);
    if (handler == null) {
      throw new IllegalStateException("No collection handler for " + reason);
    }
    return handler.execute(request, attempt);
  }

  public static CollectionJobHandlers forTesting() {
    Map<ExecutionReason, Handler> handlers = new EnumMap<>(ExecutionReason.class);
    for (ExecutionReason reason : ExecutionReason.values()) {
      handlers.put(reason, CollectionJobHandlers::fixedResult);
    }
    return new CollectionJobHandlers(handlers);
  }

  private static CollectionCompleted fixedResult(
      CollectionExecute request, AttemptContext attempt) {
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
    CollectionCompleted execute(CollectionExecute request, AttemptContext attempt)
        throws InterruptedException;
  }
}
