package com.weavo.crawler.collection.infrastructure.messaging;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.weavo.siteaccess.rule.FieldConnector;
import com.weavo.siteaccess.rule.Navigation;
import java.util.List;
import java.util.Map;

/** Payloads exchanged by Crawler and Control. Validation belongs to the boundary. */
public final class Messages {
  private Messages() {}

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

  /** Values are JSON scalars or null; a boundary validator rejects arrays and objects. */
  public record LatestRecord(Map<String, Object> fieldValues) {}

  /** Payload of a {@code collection.execute} command. */
  public record CollectionExecute(
      String sourceId,
      String executionReason,
      Integer maxDurationSeconds,
      Long navigationRevision,
      Navigation navigation,
      List<CollectionProject> projects) {}

  public record CollectionProject(
      String projectId,
      String projectSourceId,
      Long schemaVersion,
      Long connectorRevision,
      String collectionMode,
      Schema schema,
      List<FieldConnector> fieldConnectors,
      List<LatestRecord> latestRecords) {}

  /** Payload of a {@code collection.completed} event. */
  public record CollectionCompleted(
      String attemptId,
      String sourceId,
      Long navigationRevision,
      String status,
      String stopReason,
      String startedAt,
      String finishedAt,
      Long listItemCount,
      ObjectReference snapshotRef,
      List<CollectionProjectResult> projectResults,
      MessageError error) {}

  public record CollectionProjectResult(
      String projectId,
      String projectSourceId,
      Long schemaVersion,
      Long connectorRevision,
      String status,
      Boolean canIngest,
      Long normalExtractedRecordCount,
      MessageError error) {}

  /** Payload of a {@code job.issue.detected} event from a worker. */
  public record JobIssueDetected(
      String attemptId,
      String reportingService,
      String scope,
      String sourceId,
      String projectId,
      String projectSourceId,
      List<String> fieldIds,
      Long schemaVersion,
      Long connectorRevision,
      Long navigationRevision,
      MessageError error) {}

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
