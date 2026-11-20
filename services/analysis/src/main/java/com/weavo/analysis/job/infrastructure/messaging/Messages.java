package com.weavo.analysis.job.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.weavo.analysis.job.domain.FieldConnector;
import com.weavo.analysis.job.domain.Navigation;
import java.util.List;
import java.util.Map;

/** Analysis payloads exchanged with Control. Validation belongs to the boundary. */
public final class Messages {
  private Messages() {}

  /** Payload of an {@code analysis.execute} command. */
  public record AnalysisExecute(
      String analysisId,
      String projectId,
      String purpose,
      Long revision,
      @JsonInclude(JsonInclude.Include.NON_NULL) String collectionPurpose,
      Integer maxDurationSeconds,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<AnalysisSource> sources,
      @JsonInclude(JsonInclude.Include.NON_NULL) Candidate currentCandidate,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<RecoveryBaseline> recoveryBaselines,
      @JsonInclude(JsonInclude.Include.NON_NULL) FailureContext failureContext,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<IntermediateReference> intermediateRefs) {}

  public record AnalysisSource(String projectSourceId, String sourceId, String url) {}

  /** Existing intermediate result supplied by Control for an analysis retry. */
  public record IntermediateReference(String projectSourceId, ObjectReference intermediateRef) {}

  /** The schema may be null for recovery of a shared source spanning multiple projects. */
  public record Candidate(
      Schema schema,
      List<SourceConfig> sourceConfigs,
      List<String> excludedProjectSourceIds,
      List<CandidateMapping> candidateMappings) {}

  public record SourceConfig(
      String projectSourceId,
      Navigation navigation,
      Long connectorRevision,
      List<FieldConnector> fieldConnectors) {}

  public record CandidateMapping(
      String projectSourceId,
      ObjectReference intermediateRef,
      String candidateKey,
      String fieldId) {}

  public record Schema(List<SchemaField> fields) {}

  /**
   * Optional display and recommendation attributes are present where the contract supplies them.
   */
  public record SchemaField(
      String fieldId,
      String apiName,
      String displayName,
      String description,
      Integer displayOrder,
      String type,
      Boolean nullable,
      String recommendationReason) {}

  public record RecoveryBaseline(
      String projectId,
      String projectSourceId,
      Long schemaVersion,
      Long connectorRevision,
      List<LatestRecord> latestRecords,
      ObjectReference approvedSampleRef,
      Schema schema,
      List<FieldConnector> fieldConnectors) {}

  /** Values are JSON scalars or null; a boundary validator rejects arrays and objects. */
  public record LatestRecord(Map<String, Object> fieldValues) {}

  public record FailureContext(
      String sourceJobId,
      String scope,
      String sourceId,
      String projectId,
      String projectSourceId,
      List<String> fieldIds,
      MessageError error) {}

  /** Payload of an {@code analysis.completed} event. */
  public record AnalysisCompleted(
      String attemptId,
      String analysisId,
      String projectId,
      String purpose,
      Long revision,
      String status,
      Boolean schemaChangeRequired,
      Candidate candidate,
      List<AnalysisSourceResult> sourceResults,
      MessageError error) {}

  public record AnalysisSourceResult(
      String projectSourceId,
      String status,
      String validationStatus,
      ObjectReference sampleRef,
      MessageError error,
      ObjectReference intermediateRef) {}

  /** S3 object identifiers are references, not presigned URLs or credentials. */
  public record ObjectReference(String bucket, String key) {}

  /** Error details are observed facts with a shape that depends on the error code. */
  public record MessageError(
      String code,
      @JsonInclude(JsonInclude.Include.NON_NULL) String stage,
      String message,
      Map<String, Object> details,
      @JsonInclude(JsonInclude.Include.NON_NULL) Boolean retryable,
      @JsonInclude(JsonInclude.Include.NON_NULL) List<FailureEvidence> failureEvidence) {}

  public record FailureEvidence(
      String projectSourceId, String fieldId, ObjectReference ref, String expiresAt) {}
}
