package com.weavo.crawler.collection.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class ContractJsonTest {
  private final ContractJson contract = new ContractJson();
  private final JsonMapper json = JsonMapper.shared();

  @ParameterizedTest
  @ValueSource(strings = {"collection.execute"})
  void documentedCommandsRoundTripWithoutLosingValuesOrNulls(String name) throws IOException {
    String fixture = fixture(name);
    CommandEnvelope<JsonNode> command = contract.readCommand(fixture);

    assertThat(json.readTree(contract.writeCommand(command))).isEqualTo(json.readTree(fixture));
    assertThat(command.parentJobId()).isNull();
  }

  @ParameterizedTest
  @ValueSource(strings = {"collection.completed", "job.issue.detected"})
  void documentedEventsRoundTripWithoutLosingValuesOrNulls(String name) throws IOException {
    String fixture = fixture(name);
    EventEnvelope<JsonNode> event = contract.readEvent(fixture);

    assertThat(json.readTree(contract.writeEvent(event))).isEqualTo(json.readTree(fixture));
    assertThat(event.payload().get("attempt_id").isString()).isTrue();
  }

  @Test
  void exposesTheDocumentedTypedViewsWithoutDroppingRawPayload() throws IOException {
    var command = contract.readCommand(fixture("collection.execute"));
    var payload = contract.commandPayload(command, Messages.CollectionExecute.class);
    assertThat(payload.executionReason()).isEqualTo("SCHEDULED");
    assertThat(payload.projects()).hasSize(1);
    assertThat(payload.projects().getFirst().latestRecords()).hasSize(3);

    var event = contract.readEvent(fixture("collection.completed"));
    var result = contract.eventPayload(event, Messages.CollectionCompleted.class);
    assertThat(result.projectResults()).hasSize(1);
    assertThatThrownBy(() -> contract.eventPayload(event, Messages.JobIssueDetected.class))
        .isInstanceOf(ContractViolationException.class);
  }

  @Test
  void canWriteACommandFromTheTypedPayload() throws IOException {
    var raw = contract.readCommand(fixture("collection.execute"));
    var payload = contract.commandPayload(raw, Messages.CollectionExecute.class);
    var typed =
        new CommandEnvelope<>(
            raw.formatVersion(),
            raw.commandType(),
            raw.jobId(),
            raw.parentJobId(),
            raw.requestedAt(),
            raw.expiresAt(),
            payload);

    String encoded = contract.writeCommand(typed);
    JsonNode pagination = json.readTree(encoded).get("payload").get("navigation").get("pagination");
    assertThat(pagination.has("url_template")).isFalse();
    assertThat(pagination.has("start")).isFalse();
    assertThat(pagination.has("step")).isFalse();
    var decoded = contract.readCommand(encoded);
    assertThat(contract.commandPayload(decoded, Messages.CollectionExecute.class))
        .isEqualTo(payload);
  }

  @Test
  void typedEventOmitsAbsentFailureEvidence() throws IOException {
    var raw = contract.readEvent(fixture("job.issue.detected"));
    var payload = contract.eventPayload(raw, Messages.JobIssueDetected.class);
    var typed =
        new EventEnvelope<>(
            raw.formatVersion(),
            raw.eventType(),
            raw.eventId(),
            raw.jobId(),
            raw.occurredAt(),
            payload);

    JsonNode written = json.readTree(contract.writeEvent(typed));
    assertThat(written.get("payload").get("error").has("failure_evidence")).isFalse();
    assertThat(
            contract.eventPayload(
                contract.readEvent(json.writeValueAsString(written)),
                Messages.JobIssueDetected.class))
        .isEqualTo(payload);
  }

  @Test
  void keepsJsonErrorLocationAndCauseTypeWithoutExposingInput() {
    assertThatThrownBy(() -> contract.readCommand("{\"token\": INVALID_SENSITIVE_VALUE}"))
        .isInstanceOf(ContractViolationException.class)
        .hasMessageStartingWith("Invalid JSON message at line 1, column ")
        .cause()
        .hasMessage("StreamReadException")
        .hasNoCause()
        .hasNoSuppressedExceptions();
  }

  @Test
  void rejectsMissingRequiredFieldsButAcceptsExplicitlyNullParent() throws IOException {
    ObjectNode command = (ObjectNode) json.readTree(fixture("collection.execute"));
    assertThat(contract.readCommand(json.writeValueAsString(command)).parentJobId()).isNull();
    command.remove("parent_job_id");
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(command)))
        .isInstanceOf(ContractViolationException.class);
  }

  @Test
  void rejectsInvalidUuidTimestampAndVersionWithoutLeakingThePayload() throws IOException {
    ObjectNode command = (ObjectNode) json.readTree(fixture("collection.execute"));
    command.put("job_id", "INVALID-SENSITIVE-VALUE");
    ObjectNode badJob = command;
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(badJob)))
        .isInstanceOf(ContractViolationException.class)
        .hasMessageNotContaining("INVALID-SENSITIVE-VALUE");

    command = (ObjectNode) json.readTree(fixture("collection.execute"));
    command.put("requested_at", "2026-11-06T09:00:00+09:00");
    ObjectNode badTime = command;
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(badTime)))
        .isInstanceOf(ContractViolationException.class);

    command = (ObjectNode) json.readTree(fixture("collection.execute"));
    ((ObjectNode) command.get("payload")).put("navigation_revision", 0);
    ObjectNode badVersion = command;
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(badVersion)))
        .isInstanceOf(ContractViolationException.class);
  }

  @Test
  void distinguishesPayloadBindingErrorsFromNavigationRuleViolations() throws IOException {
    var command = (ObjectNode) json.readTree(fixture("collection.execute"));
    var pagination = (ObjectNode) command.get("payload").get("navigation").get("pagination");
    pagination.put("type", "INVALID_SENSITIVE_VALUE");
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(command)))
        .isInstanceOf(ContractViolationException.class)
        .hasMessage("$.payload has an invalid shape")
        .cause()
        .hasMessageNotContaining("INVALID_SENSITIVE_VALUE")
        .hasNoCause();

    pagination.put("type", "CLICK_NEXT");
    pagination.put("wait_selector", ".notice-list");
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(command)))
        .isInstanceOf(ContractViolationException.class)
        .hasMessage("$.payload violates Navigation rules")
        .cause()
        .hasMessage("IllegalArgumentException");
  }

  @Test
  void rejectsAWorkerEventWithoutAttemptId() throws IOException {
    ObjectNode event = (ObjectNode) json.readTree(fixture("collection.completed"));
    ((ObjectNode) event.get("payload")).remove("attempt_id");

    assertThatThrownBy(() -> contract.readEvent(json.writeValueAsString(event)))
        .isInstanceOf(ContractViolationException.class);
  }

  @Test
  void rejectsInvalidModeAndNonscalarLatestValues() throws IOException {
    ObjectNode command = (ObjectNode) json.readTree(fixture("collection.execute"));
    var project = (ObjectNode) command.get("payload").get("projects").get(0);
    project.put("collection_mode", "FULL");
    ObjectNode invalidMode = command;
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(invalidMode)))
        .isInstanceOf(ContractViolationException.class);

    command = (ObjectNode) json.readTree(fixture("collection.execute"));
    var values =
        (ObjectNode)
            command
                .get("payload")
                .get("projects")
                .get(0)
                .get("latest_records")
                .get(0)
                .get("field_values");
    values.putObject("ba4f9e1e-ae8e-4a3c-9261-dca457848148").put("nested", 1);
    ObjectNode invalidValues = command;
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(invalidValues)))
        .isInstanceOf(ContractViolationException.class);
  }

  @Test
  void keepsUnknownPayloadFieldsAndMissingOptionalFieldsWhenReemitting() throws IOException {
    ObjectNode event = (ObjectNode) json.readTree(fixture("job.issue.detected"));
    var payload = (ObjectNode) event.get("payload");
    payload.put("future_field", "visible");
    var error = (ObjectNode) payload.get("error");
    assertThat(error.has("failure_evidence")).isFalse();

    var decoded = contract.readEvent(json.writeValueAsString(event));
    JsonNode reemitted = json.readTree(contract.writeEvent(decoded));
    assertThat(reemitted).isEqualTo(event);
    assertThat(reemitted.get("payload").get("error").has("failure_evidence")).isFalse();
  }

  @Test
  void latestRecordMustIncludeEverySchemaFieldButCanContainNull() throws IOException {
    ObjectNode command = (ObjectNode) json.readTree(fixture("collection.execute"));
    var project = command.get("payload").get("projects").get(0);
    String fieldId = project.get("schema").get("fields").get(0).get("field_id").asText();
    var values = (ObjectNode) project.get("latest_records").get(0).get("field_values");
    values.putNull(fieldId);
    contract.readCommand(json.writeValueAsString(command));
    values.remove(fieldId);
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(command)))
        .isInstanceOf(ContractViolationException.class);
  }

  @Test
  void crawlerIssueRequiresItsOwnAttemptAndReportingService() throws IOException {
    ObjectNode event = (ObjectNode) json.readTree(fixture("job.issue.detected"));
    var payload = (ObjectNode) event.get("payload");
    payload.remove("attempt_id");
    assertThatThrownBy(() -> contract.readEvent(json.writeValueAsString(event)))
        .isInstanceOf(ContractViolationException.class);
    payload.put("attempt_id", "43f5b110-f193-4157-91f2-1a16b8fb70d6");
    payload.put("reporting_service", "INGESTION");
    assertThatThrownBy(() -> contract.readEvent(json.writeValueAsString(event)))
        .isInstanceOf(ContractViolationException.class);
  }

  @Test
  void collectionCannotIngestWithoutASnapshot() throws IOException {
    ObjectNode event = (ObjectNode) json.readTree(fixture("collection.completed"));
    ((ObjectNode) event.get("payload")).putNull("snapshot_ref");
    ((ObjectNode) event.get("payload").get("project_results").get(0)).put("can_ingest", true);
    assertThatThrownBy(() -> contract.readEvent(json.writeValueAsString(event)))
        .isInstanceOf(ContractViolationException.class);
  }

  @Test
  void collectionBudgetDependsOnWhetherAnyProjectNeedsFullCollection() throws IOException {
    ObjectNode command = (ObjectNode) json.readTree(fixture("collection.execute"));
    var payload = (ObjectNode) command.get("payload");
    payload.put("max_duration_seconds", 61);
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(command)))
        .isInstanceOf(ContractViolationException.class);
    var project = (ObjectNode) payload.get("projects").get(0);
    project.put("collection_mode", "FULL");
    project.putArray("latest_records");
    payload.put("max_duration_seconds", 300);
    contract.readCommand(json.writeValueAsString(command));
    payload.put("max_duration_seconds", 301);
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(command)))
        .isInstanceOf(ContractViolationException.class);
  }

  @Test
  void optionalErrorFieldsRoundTripWithoutInventingAStage() throws IOException {
    ObjectNode event = (ObjectNode) json.readTree(fixture("job.issue.detected"));
    ObjectNode error = (ObjectNode) event.get("payload").get("error");
    error.remove("stage");
    error.put("retryable", true);
    var decoded = contract.readEvent(json.writeValueAsString(event));
    var typed = contract.eventPayload(decoded, Messages.JobIssueDetected.class);
    assertThat(typed.error().stage()).isNull();
    assertThat(typed.error().retryable()).isTrue();
    var outgoing =
        new EventEnvelope<>(
            decoded.formatVersion(),
            decoded.eventType(),
            decoded.eventId(),
            decoded.jobId(),
            decoded.occurredAt(),
            typed);
    var encoded = json.readTree(contract.writeEvent(outgoing));
    assertThat(encoded.get("payload").get("error").has("stage")).isFalse();
    assertThat(encoded.get("payload").get("error").get("retryable").asBoolean()).isTrue();
    error.putNull("stage");
    assertThatThrownBy(() -> contract.readEvent(json.writeValueAsString(event)))
        .isInstanceOf(ContractViolationException.class);
    error.remove("stage");
    error.put("retryable", "true");
    assertThatThrownBy(() -> contract.readEvent(json.writeValueAsString(event)))
        .isInstanceOf(ContractViolationException.class);
  }

  @Test
  void rejectsMissingExtractionAttributeAndNullOptionalPaginationFields() throws IOException {
    ObjectNode command = (ObjectNode) json.readTree(fixture("collection.execute"));
    ObjectNode payload = (ObjectNode) command.get("payload");
    ObjectNode extract =
        (ObjectNode) payload.get("projects").get(0).get("field_connectors").get(0).get("extract");
    extract.remove("attribute");
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(command)))
        .isInstanceOf(ContractViolationException.class);
    extract.putNull("attribute");
    ObjectNode pagination = (ObjectNode) payload.get("navigation").get("pagination");
    pagination.putNull("url_template");
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(command)))
        .isInstanceOf(ContractViolationException.class);
  }

  private String fixture(String name) throws IOException {
    try (var stream = getClass().getResourceAsStream("/contracts/" + name + ".json")) {
      assertThat(stream).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
