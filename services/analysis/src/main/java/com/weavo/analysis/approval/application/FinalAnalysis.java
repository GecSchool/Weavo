package com.weavo.analysis.approval.application;

import com.weavo.analysis.job.infrastructure.messaging.Messages.AnalysisCompleted;
import com.weavo.analysis.job.infrastructure.messaging.Messages.AnalysisExecute;
import com.weavo.analysis.job.infrastructure.messaging.Messages.AnalysisSourceResult;
import com.weavo.analysis.job.infrastructure.messaging.Messages.Candidate;
import com.weavo.analysis.job.infrastructure.messaging.Messages.MessageError;
import com.weavo.analysis.job.infrastructure.messaging.Messages.ObjectReference;
import com.weavo.analysis.job.infrastructure.messaging.Messages.SourceConfig;
import com.weavo.analysis.joint.application.AnalysisArtifacts;
import com.weavo.analysis.joint.application.JointAnalysis;
import com.weavo.analysis.source.application.SourceAnalyzer;
import com.weavo.analysis.source.application.SourceCandidate;
import com.weavo.analysis.source.application.SourceCandidateGenerator.Unavailable;
import com.weavo.extraction.FieldValueConverter.Type;
import com.weavo.siteaccess.SiteAccessException;
import com.weavo.siteaccess.rule.Navigation;
import com.weavo.worker.application.AttemptContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Finalizes a candidate only after each affected source has been replayed and its sample saved. */
public final class FinalAnalysis {
  private final JointAnalysis joint;
  private final Verification verification;
  private final ApprovalSamples samples;

  public FinalAnalysis(
      JointAnalysis joint, Verification verification, AnalysisArtifacts artifacts) {
    this.joint = joint;
    this.verification = verification;
    this.samples = new ApprovalSamples(artifacts);
  }

  public AnalysisCompleted execute(String jobId, AnalysisExecute request, AttemptContext attempt)
      throws InterruptedException {
    boolean jointRequest = Set.of("INITIAL", "RETRY_SOURCE").contains(request.purpose());
    if (!jointRequest && !"REVALIDATE".equals(request.purpose())) {
      return completed(
          request,
          attempt,
          "FAILED",
          null,
          List.of(),
          new MessageError(
              "TECHNICAL_FAILURE",
              "ANALYSIS_DISPATCH",
              "해당 분석 작업의 처리기가 준비되지 않았습니다.",
              Map.of(),
              null,
              null));
    }
    final Candidate candidate;
    Map<String, ObjectReference> refs = Map.of();
    Map<String, AnalysisSourceResult> results = new LinkedHashMap<>();
    try {
      if (jointRequest) {
        var proposal = joint.prepare(jobId, request, attempt);
        candidate = proposal.candidate();
        refs = proposal.intermediates();
        proposal
            .failures()
            .forEach((id, failure) -> results.put(id, failed(id, failure, null, null)));
      } else {
        candidate = request.currentCandidate();
      }
    } catch (Unavailable error) {
      return technical(request, attempt, "LLM_CALL", error.getMessage());
    } catch (SiteAccessException error) {
      if (error.kind() == SiteAccessException.Kind.CANCELLED) {
        throw new java.util.concurrent.CancellationException("The analysis attempt was cancelled");
      }
      return technical(request, attempt, "TIME_LIMIT", error.kind().name());
    } catch (IllegalArgumentException error) {
      return technical(request, attempt, "CANDIDATE", "INVALID_JOINT_CANDIDATE");
    }
    if (candidate == null) {
      return completed(
          request, attempt, "FAILED", null, ordered(request, results), firstError(results));
    }

    var configs = new ArrayList<SourceConfig>();
    var targets = new HashSet<String>();
    var requestedUrls = new LinkedHashMap<String, String>();
    for (var requested : request.sources()) {
      if (!targets.add(requested.projectSourceId())) {
        return technical(request, attempt, "CANDIDATE", "DUPLICATE_VALIDATION_SOURCE");
      }
      requestedUrls.put(requested.projectSourceId(), requested.url());
    }
    if ("RETRY_SOURCE".equals(request.purpose())
        && !candidate.schema().equals(request.currentCandidate().schema())) {
      candidate.sourceConfigs().forEach(config -> targets.add(config.projectSourceId()));
    }
    var excluded = Set.copyOf(candidate.excludedProjectSourceIds());
    for (SourceConfig config : candidate.sourceConfigs()) {
      String id = config.projectSourceId();
      if (!targets.contains(id) || excluded.contains(id) || results.containsKey(id)) {
        configs.add(config);
        continue;
      }
      SourceAnalyzer.Verification check;
      try {
        var requestedUrl = requestedUrls.get(id);
        if (requestedUrl != null
            && !Navigation.normalizedListUrl(requestedUrl)
                .equals(Navigation.normalizedListUrl(config.navigation().listUrl()))) {
          throw new IllegalArgumentException("Final validation uses a different source URL");
        }
        check = verification.verify(source(candidate, config), attempt);
      } catch (IllegalArgumentException error) {
        check =
            new SourceAnalyzer.Verification(
                null,
                List.of(),
                new SourceAnalyzer.Failure("CANDIDATE", null, "INVALID_FIELD_MAPPING"));
      }
      var failure = check.failure();
      if (failure == null
          && (check.navigation() == null
              || check.samples().isEmpty()
              || check.samples().stream()
                  .anyMatch(row ->
                      !row.complete()
                          || row.fields().size() != candidate.schema().fields().size()))) {
        failure = new SourceAnalyzer.Failure("CANDIDATE", null, "INCOMPLETE_VALIDATION_SAMPLE");
      }

      ObjectReference sample =
          (failure != null
                  && ("PARSER_CALL".equals(failure.stage())
                      || "TIME_LIMIT".equals(failure.stage())))
              ? null
              : samples.save(jobId, request, id, check.samples(), attempt);
      if (failure == null && sample == null) {
        failure = new SourceAnalyzer.Failure("LIST", null, "NO_VALIDATION_SAMPLES");
      }
      if (failure != null) {
        results.put(id, failed(id, failure, sample, refs.get(id)));
        configs.add(config);
      } else {
        configs.add(
            new SourceConfig(
                id, check.navigation(), config.connectorRevision(), config.fieldConnectors()));
        results.put(
            id, new AnalysisSourceResult(id, "SUCCEEDED", "PASSED", sample, null, refs.get(id)));
      }
    }
    for (String id : targets) {
      if (excluded.contains(id)) {
        results.putIfAbsent(
            id, new AnalysisSourceResult(id, "SKIPPED", "UNVERIFIABLE", null, null, null));
      } else {
        results.putIfAbsent(
            id,
            failed(
                id,
                new SourceAnalyzer.Failure("CANDIDATE", null, "SOURCE_CONFIG_MISSING"),
                null,
                null));
      }
    }
    var verified =
        new Candidate(
            candidate.schema(),
            configs,
            candidate.excludedProjectSourceIds(),
            candidate.candidateMappings());
    long successes = results.values().stream().filter(r -> r.status().equals("SUCCEEDED")).count();
    if (successes == 0) {
      return completed(
          request,
          attempt,
          "FAILED",
          "INITIAL".equals(request.purpose()) ? null : verified,
          ordered(request, results),
          firstError(results));
    }
    boolean partial = results.values().stream().anyMatch(r -> r.status().equals("FAILED"));
    return completed(
        request,
        attempt,
        partial ? "PARTIAL" : "SUCCEEDED",
        verified,
        ordered(request, results),
        null);
  }

  private static SourceCandidate source(Candidate candidate, SourceConfig config) {
    var connectors =
        config.fieldConnectors().stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    connector -> connector.fieldId(),
                    connector -> connector,
                    (one, two) -> {
                      throw new IllegalArgumentException("Duplicate field connector");
                    }));
    var fields =
        candidate.schema().fields().stream()
            .map(field -> {
              var connector = connectors.get(field.fieldId());
              if (connector == null) {
                throw new IllegalArgumentException("Missing field connector");
              }
              return new SourceCandidate.Field(
                  field.displayName(),
                  Type.valueOf(field.type().toUpperCase(Locale.ROOT)),
                  field.nullable(),
                  field.recommendationReason() == null
                      ? field.displayName()
                      : field.recommendationReason(),
                  connector);
            })
            .toList();
    if (connectors.size() != fields.size()) {
      throw new IllegalArgumentException("Field schema does not match connectors");
    }
    return new SourceCandidate(config.navigation(), fields);
  }

  private static List<AnalysisSourceResult> ordered(
      AnalysisExecute request, Map<String, AnalysisSourceResult> results) {
    var copy = new LinkedHashMap<>(results);
    var ordered = new ArrayList<AnalysisSourceResult>();
    request
        .sources()
        .forEach(source -> {
          var result = copy.remove(source.projectSourceId());
          if (result != null) {
            ordered.add(result);
          }
        });
    ordered.addAll(copy.values());
    return List.copyOf(ordered);
  }

  private static MessageError firstError(Map<String, AnalysisSourceResult> results) {
    return results.values().stream()
        .map(AnalysisSourceResult::error)
        .filter(java.util.Objects::nonNull)
        .findFirst()
        .orElse(
            new MessageError(
                "FIELD_EXTRACTION_FAILED",
                "ANALYSIS_VALIDATION",
                "검증한 출처가 없습니다.",
                Map.of(),
                null,
                null));
  }

  private static AnalysisSourceResult failed(
      String id,
      SourceAnalyzer.Failure failure,
      ObjectReference sample,
      ObjectReference intermediate) {
    var error = ApprovalSamples.error(failure);
    String status = error.code().equals("TECHNICAL_FAILURE") ? "UNVERIFIABLE" : "FAILED";
    return new AnalysisSourceResult(id, "FAILED", status, sample, error, intermediate);
  }

  private static AnalysisCompleted technical(
      AnalysisExecute request, AttemptContext attempt, String stage, String reason) {
    var error =
        new MessageError(
            "TECHNICAL_FAILURE", stage, "분석을 완료할 수 없습니다.", Map.of("reason", reason), null, null);
    return completed(request, attempt, "FAILED", null, List.of(), error);
  }

  private static AnalysisCompleted completed(
      AnalysisExecute request,
      AttemptContext attempt,
      String status,
      Candidate candidate,
      List<AnalysisSourceResult> results,
      MessageError error) {
    return new AnalysisCompleted(
        attempt.id().toString(),
        request.analysisId(),
        request.projectId(),
        request.purpose(),
        request.revision(),
        status,
        false,
        candidate,
        results,
        error);
  }

  @FunctionalInterface
  public interface Verification {
    SourceAnalyzer.Verification verify(SourceCandidate source, AttemptContext attempt)
        throws InterruptedException;
  }
}
