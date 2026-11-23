package com.weavo.analysis.job.application;

import com.weavo.analysis.job.infrastructure.messaging.Messages.AnalysisCompleted;
import com.weavo.analysis.job.infrastructure.messaging.Messages.AnalysisExecute;
import com.weavo.analysis.job.infrastructure.messaging.Messages.AnalysisSourceResult;
import com.weavo.analysis.job.infrastructure.messaging.Messages.MessageError;
import com.weavo.worker.application.AttemptContext;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Routes an accepted analysis attempt to its purpose-specific handler. */
public final class AnalysisJobHandlers {
  private final Map<Purpose, Handler> handlers;

  public AnalysisJobHandlers(Map<Purpose, Handler> handlers) {
    this.handlers = Map.copyOf(handlers);
  }

  public AnalysisCompleted execute(String jobId, AnalysisExecute request, AttemptContext attempt)
      throws InterruptedException {
    var purpose = Purpose.valueOf(request.purpose());
    var handler = handlers.get(purpose);
    if (handler == null) {
      throw new IllegalStateException("No analysis handler for " + purpose);
    }
    return handler.execute(jobId, request, attempt);
  }

  public static AnalysisJobHandlers forTesting() {
    Map<Purpose, Handler> handlers = new EnumMap<>(Purpose.class);
    for (Purpose purpose : Purpose.values()) {
      handlers.put(purpose, AnalysisJobHandlers::fixedResult);
    }
    return new AnalysisJobHandlers(handlers);
  }

  private static AnalysisCompleted fixedResult(
      String jobId, AnalysisExecute request, AttemptContext attempt) {
    var error =
        new MessageError(
            "TECHNICAL_FAILURE", null, "테스트 처리기는 실제 분석을 실행하지 않습니다.", Map.of(), false, null);
    List<AnalysisSourceResult> sourceResults =
        request.sources() == null
            ? List.of()
            : request.sources().stream()
                .map(source ->
                    new AnalysisSourceResult(
                        source.projectSourceId(), "FAILED", "UNVERIFIABLE", null, error, null))
                .toList();
    return new AnalysisCompleted(
        attempt.id().toString(),
        request.analysisId(),
        request.projectId(),
        request.purpose(),
        request.revision(),
        "FAILED",
        false,
        null,
        null,
        sourceResults,
        error);
  }

  public enum Purpose {
    INITIAL,
    RETRY_SOURCE,
    ADD_SOURCE,
    REVALIDATE,
    REPAIR_NAVIGATION,
    REPAIR_CONNECTOR,
    RECHECK_SOURCE
  }

  @FunctionalInterface
  public interface Handler {
    AnalysisCompleted execute(String jobId, AnalysisExecute request, AttemptContext attempt)
        throws InterruptedException;
  }
}
