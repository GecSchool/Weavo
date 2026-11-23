package com.weavo.analysis.approval.application;

import com.weavo.analysis.job.infrastructure.messaging.Messages.AnalysisExecute;
import com.weavo.analysis.job.infrastructure.messaging.Messages.MessageError;
import com.weavo.analysis.job.infrastructure.messaging.Messages.ObjectReference;
import com.weavo.analysis.joint.application.AnalysisArtifacts;
import com.weavo.analysis.source.application.SourceAnalyzer;
import com.weavo.worker.application.AttemptContext;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

final class ApprovalSamples {
  private final AnalysisArtifacts artifacts;

  ApprovalSamples(AnalysisArtifacts artifacts) {
    this.artifacts = artifacts;
  }

  ObjectReference save(
      String jobId,
      AnalysisExecute request,
      String source,
      List<SourceAnalyzer.Sample> observed,
      AttemptContext attempt)
      throws InterruptedException {
    if (observed.isEmpty()) {
      return null;
    }
    var samples = new ArrayList<Sample>();
    Instant captured = attempt.now();
    String root = "analysis/" + request.analysisId();
    String path = source + AnalysisArtifacts.attemptPath(jobId, attempt);
    for (int index = 0; index < observed.size(); index++) {
      var row = observed.get(index);
      var fields = new ArrayList<FieldSample>();
      for (var field : row.fields()) {
        var result = field.result();
        var raw = result.extraction().rawValues();
        var problem = result.failure();
        if (problem == null) {
          fields.add(
              new FieldSample(
                  field.fieldId(),
                  raw,
                  result.extraction().matchCount(),
                  null,
                  result.value(),
                  result.status().name(),
                  null,
                  null));
          continue;
        }
        ObjectReference ref = null;
        String expires = null;
        if (!raw.isEmpty()) {
          String key =
              root
                  + "/failures/"
                  + path
                  + "/sample-"
                  + index
                  + "/field-"
                  + field.fieldId()
                  + ".json";
          ref =
              artifacts.put(
                  key,
                  new FailureRaw(
                      1,
                      request.analysisId(),
                      request.revision(),
                      request.projectId(),
                      source,
                      index,
                      field.fieldId(),
                      captured.toString(),
                      raw),
                  attempt);
          expires = captured.plus(7, ChronoUnit.DAYS).toString();
        }
        var failure =
            new SourceAnalyzer.Failure(problem.stage().name(), field.fieldId(), problem.reason());
        fields.add(
            new FieldSample(
                field.fieldId(),
                List.of(),
                result.extraction().matchCount(),
                ref,
                null,
                "FAILED",
                error(failure),
                expires));
      }
      samples.add(new Sample(index, row.sourceUrl(), fields));
    }
    String key = root + "/revisions/" + request.revision() + "/" + path + "/sample.json";
    return artifacts.put(
        key,
        new SampleSet(
            1,
            request.analysisId(),
            request.revision(),
            request.projectId(),
            source,
            captured.toString(),
            samples),
        attempt);
  }

  static MessageError error(SourceAnalyzer.Failure failure) {
    var details =
        failure.candidateKey() == null
            ? Map.<String, Object>of("reason", failure.reason())
            : Map.<String, Object>of(
                "reason", failure.reason(), "field_id", failure.candidateKey());
    String code =
        switch (failure.stage()) {
          case "EXTRACTION" -> "FIELD_EXTRACTION_FAILED";
          case "PARSER", "PARSER_INPUT" -> "PARSER_FAILED";
          case "TYPE_CONVERSION" -> "TYPE_CONVERSION_FAILED";
          case "PARSER_CALL", "LLM_CALL", "TIME_LIMIT" -> "TECHNICAL_FAILURE";
          case "ACCESS" ->
              switch (failure.reason()) {
                case "ROBOTS_DENIED", "ACCESS_BLOCKED", "INVALID_URL" -> "ACCESS_BLOCKED";
                case "HTTP_TIMEOUT", "HTTP_STATUS", "SITE_UNAVAILABLE" -> "SITE_UNAVAILABLE";
                default -> "BROWSER_FAILED";
              };
          case "LIST_NAVIGATION", "DETAIL_NAVIGATION", "DETAIL_RETURN", "NAVIGATION" ->
              "NAVIGATION_FAILED";
          case "LIST" -> "EMPTY_LIST";
          default -> "FIELD_EXTRACTION_FAILED";
        };
    return new MessageError(code, failure.stage(), "출처의 분석 규칙을 검증하지 못했습니다.", details, null, null);
  }

  private record SampleSet(
      int formatVersion,
      String analysisId,
      long revision,
      String projectId,
      String projectSourceId,
      String capturedAt,
      List<Sample> samples) {}

  private record Sample(int sampleIndex, String sourceUrl, List<FieldSample> fields) {}

  private record FieldSample(
      String fieldId,
      List<String> rawValues,
      Integer matchCount,
      ObjectReference failureRawRef,
      Object value,
      String status,
      MessageError error,
      String failureExpiresAt) {}

  private record FailureRaw(
      int formatVersion,
      String analysisId,
      long revision,
      String projectId,
      String projectSourceId,
      int sampleIndex,
      String fieldId,
      String observedAt,
      List<String> rawValues) {}
}
