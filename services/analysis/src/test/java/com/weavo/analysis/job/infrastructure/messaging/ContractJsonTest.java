package com.weavo.analysis.job.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class ContractJsonTest {
  private final ContractJson contract = new ContractJson();
  private final JsonMapper json = JsonMapper.shared();

  @Test
  void documentedAnalysisCommandRoundTripsAndExposesTypedValues() throws IOException {
    String original = fixture("analysis.execute");
    var command = contract.readCommand(original);
    var payload = contract.commandPayload(command, Messages.AnalysisExecute.class);
    assertThat(payload.purpose()).isEqualTo("INITIAL");
    assertThat(payload.sources()).hasSize(1);
    assertThat(command.parentJobId()).isNull();
    assertThat(json.readTree(contract.writeCommand(command))).isEqualTo(json.readTree(original));
  }

  @Test
  void documentedAnalysisEventRoundTripsAndPreservesRules() throws IOException {
    String original = fixture("analysis.completed");
    var event = contract.readEvent(original);
    var payload = contract.eventPayload(event, Messages.AnalysisCompleted.class);
    assertThat(payload.candidate().sourceConfigs().getFirst().fieldConnectors()).hasSize(1);
    assertThat(payload.candidate().schema().fields().getFirst().apiName()).isEqualTo("title");
    assertThat(json.readTree(contract.writeEvent(event))).isEqualTo(json.readTree(original));
  }

  @Test
  void typedCommandCanBeWrittenAndRead() throws IOException {
    var raw = contract.readCommand(fixture("analysis.execute"));
    var payload = contract.commandPayload(raw, Messages.AnalysisExecute.class);
    var typed =
        new CommandEnvelope<>(
            raw.formatVersion(),
            raw.commandType(),
            raw.jobId(),
            raw.parentJobId(),
            raw.requestedAt(),
            raw.expiresAt(),
            payload);
    var decoded = contract.readCommand(contract.writeCommand(typed));
    assertThat(contract.commandPayload(decoded, Messages.AnalysisExecute.class)).isEqualTo(payload);
  }

  @Test
  void sharedNavigationRepairCanHaveNoTopLevelProjectOrSchema() throws IOException {
    ObjectNode event = (ObjectNode) json.readTree(fixture("analysis.completed"));
    var payload = (ObjectNode) event.get("payload");
    payload.put("purpose", "REPAIR_NAVIGATION");
    payload.putNull("project_id");
    ((ObjectNode) payload.get("candidate")).putNull("schema");
    var decoded = contract.readEvent(json.writeValueAsString(event));
    var typed = contract.eventPayload(decoded, Messages.AnalysisCompleted.class);
    assertThat(typed.projectId()).isNull();
    assertThat(typed.candidate().schema()).isNull();
    assertThat(json.readTree(contract.writeEvent(decoded))).isEqualTo(event);
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
  void rejectsMissingRequiredEnvelopeFieldAndInvalidIdentifier() throws IOException {
    ObjectNode command = (ObjectNode) json.readTree(fixture("analysis.execute"));
    command.remove("parent_job_id");
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(command)))
        .isInstanceOf(ContractViolationException.class);
    command.putNull("parent_job_id");
    command.put("job_id", "INVALID-SENSITIVE-VALUE");
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(command)))
        .isInstanceOf(ContractViolationException.class)
        .hasMessageNotContaining("INVALID-SENSITIVE-VALUE");
  }

  @Test
  void rejectsWrongRevisionAndNonUtcTimestamp() throws IOException {
    ObjectNode command = (ObjectNode) json.readTree(fixture("analysis.execute"));
    ((ObjectNode) command.get("payload")).put("revision", 0);
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(command)))
        .isInstanceOf(ContractViolationException.class);
    ((ObjectNode) command.get("payload")).put("revision", 1);
    command.put("requested_at", "2026-11-06T09:00:00+09:00");
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(command)))
        .isInstanceOf(ContractViolationException.class);
  }

  @Test
  void rejectsInvalidRuleCombinationsInAnalysisResult() throws IOException {
    ObjectNode event = (ObjectNode) json.readTree(fixture("analysis.completed"));
    var config = event.get("payload").get("candidate").get("source_configs").get(0);
    var navigation = (ObjectNode) config.get("navigation");
    var pagination = (ObjectNode) navigation.get("pagination");
    pagination.put("type", "CLICK_NEXT");
    pagination.put("wait_selector", ".notice-list");
    assertThatThrownBy(() -> contract.readEvent(json.writeValueAsString(event)))
        .isInstanceOf(ContractViolationException.class)
        .hasMessage("$.payload violates Navigation rules")
        .cause()
        .hasMessage("IllegalArgumentException");
  }

  @Test
  void keepsUnknownPayloadFieldsAndMissingOptionalFieldsWhenReadingAndWriting() throws IOException {
    ObjectNode event = (ObjectNode) json.readTree(fixture("analysis.completed"));
    var payload = (ObjectNode) event.get("payload");
    payload.put("future_field", "visible");
    assertThat(payload.get("source_results").get(0).has("sample_revision")).isFalse();
    var decoded = contract.readEvent(json.writeValueAsString(event));
    assertThat(json.readTree(contract.writeEvent(decoded))).isEqualTo(event);
  }

  @Test
  void repairBaselineRequiresEverySchemaFieldButAllowsNull() throws IOException {
    var command = sharedRepairCommand();
    var values =
        (ObjectNode)
            command
                .get("payload")
                .get("recovery_baselines")
                .get(0)
                .get("latest_records")
                .get(0)
                .get("field_values");
    String fieldId = "ba4f9e1e-ae8e-4a3c-9261-dca457848148";
    var decoded = contract.readCommand(json.writeValueAsString(command));
    var typed = contract.commandPayload(decoded, Messages.AnalysisExecute.class);
    assertThat(typed.recoveryBaselines().getFirst().latestRecords().getFirst().fieldValues())
        .containsEntry(fieldId, null);
    values.remove(fieldId);
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(command)))
        .isInstanceOf(ContractViolationException.class);
  }

  @Test
  void bothRepairsRequireExistingNavigationEvenWhenErrorHasPageUrl() throws IOException {
    var command = sharedRepairCommand();
    var payload = (ObjectNode) command.get("payload");
    assertRepairRequiresNavigation(command);

    useConnectorRepair(payload);
    assertRepairRequiresNavigation(command);
  }

  @Test
  void sharedRepairRequiresMatchingTargetsRevisionsAndListRules() throws IOException {
    var command = sharedRepairCommand();
    var payload = command.get("payload");
    var configs = (ArrayNode) payload.get("current_candidate").get("source_configs");
    var baselines = (ArrayNode) payload.get("recovery_baselines");
    String secondSource = "95f7d7de-ec00-4fbe-b430-fa3a79763c19";
    var secondConfig = ((ObjectNode) configs.get(0)).deepCopy();
    secondConfig.put("project_source_id", secondSource);
    var secondBaseline = ((ObjectNode) baselines.get(0)).deepCopy();
    secondBaseline.put("project_source_id", secondSource);
    configs.add(secondConfig);
    baselines.add(secondBaseline);

    var secondNavigation = (ObjectNode) secondConfig.get("navigation");
    String listUrl = secondNavigation.get("list_url").asText();
    secondNavigation.put("list_url", listUrl + "#top");
    assertThat(contract.readCommand(json.writeValueAsString(command)).payload()).isEqualTo(payload);

    secondNavigation.put("list_url", listUrl + "?category=other");
    assertRejected(command, "source_configs.navigation");
    secondNavigation.put("list_url", listUrl);

    secondBaseline.put("connector_revision", 99);
    assertRejected(command, "recovery_baselines");
    secondBaseline.put("connector_revision", secondConfig.get("connector_revision").intValue());

    secondBaseline.put("project_source_id", baselines.get(0).get("project_source_id").asText());
    assertRejected(command, "recovery_baselines");
  }

  @Test
  void connectorRepairRequiresExactlyTheFailedProjectSource() throws IOException {
    var command = sharedRepairCommand();
    var payload = (ObjectNode) command.get("payload");
    useConnectorRepair(payload);
    assertThat(contract.readCommand(json.writeValueAsString(command)).payload()).isEqualTo(payload);

    ((ObjectNode) payload.get("failure_context"))
        .put("project_source_id", "95f7d7de-ec00-4fbe-b430-fa3a79763c19");
    assertRejected(command, "failure_context");
  }

  private void assertRepairRequiresNavigation(ObjectNode command) {
    var payload = (ObjectNode) command.get("payload");
    var candidate = (ObjectNode) payload.remove("current_candidate");
    assertRejected(command, "current_candidate");
    payload.putNull("current_candidate");
    assertRejected(command, "current_candidate");
    payload.set("current_candidate", candidate);
    var configs = (ArrayNode) candidate.get("source_configs");
    var config = (ObjectNode) configs.remove(0);
    assertRejected(command, "source_configs");
    configs.add(config);
    var navigation = config.remove("navigation");
    assertRejected(command, "navigation");
    config.set("navigation", navigation);
  }

  private void assertRejected(ObjectNode command, String path) {
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(command)))
        .isInstanceOf(ContractViolationException.class)
        .hasMessageContaining(path);
  }

  private static void useConnectorRepair(ObjectNode payload) {
    var baseline = payload.get("recovery_baselines").get(0);
    var failure = (ObjectNode) payload.get("failure_context");
    payload.put("purpose", "REPAIR_CONNECTOR");
    payload.set("project_id", baseline.get("project_id"));
    failure.put("scope", "PROJECT_SOURCE");
    failure.set("project_id", baseline.get("project_id"));
    failure.set("project_source_id", baseline.get("project_source_id"));
  }

  private ObjectNode sharedRepairCommand() throws IOException {
    ObjectNode command = (ObjectNode) json.readTree(fixture("analysis.execute"));
    ObjectNode payload = (ObjectNode) command.get("payload");
    JsonNode originalCandidate =
        json.readTree(fixture("analysis.completed")).get("payload").get("candidate");
    JsonNode sourceConfig = originalCandidate.get("source_configs").get(0);
    payload.put("purpose", "REPAIR_NAVIGATION");
    payload.putNull("project_id");
    var candidate = (ObjectNode) originalCandidate.deepCopy();
    candidate.putNull("schema");
    payload.set("current_candidate", candidate);
    var baseline = payload.putArray("recovery_baselines").addObject();
    baseline.put("project_id", "71c46a95-e328-47a5-92e7-a9018e5a5de6");
    baseline.put("project_source_id", "95f7d7de-ec00-4fbe-b430-fa3a79763c18");
    baseline.put("schema_version", 1);
    baseline.put("connector_revision", 2);
    baseline.putNull("approved_sample_ref");
    baseline.set("schema", originalCandidate.get("schema").deepCopy());
    baseline.set("field_connectors", sourceConfig.get("field_connectors").deepCopy());
    var values = baseline.putArray("latest_records").addObject().putObject("field_values");
    var field = originalCandidate.get("schema").get("fields").get(0);
    values.putNull(field.get("field_id").asText());
    var context = payload.putObject("failure_context");
    context.put("source_job_id", "6a2f36b6-0524-4bdb-b66a-c6cf27aafdfc");
    context.put("scope", "SOURCE");
    context.put("source_id", "1c226765-48b1-4518-ad16-057e8998eb84");
    context.putNull("project_id");
    context.putNull("project_source_id");
    context.putArray("field_ids");
    var error = context.putObject("error");
    error.put("code", "NAVIGATION_FAILED");
    error.put("stage", "LIST_NAVIGATION");
    error.put("message", "이동 실패");
    error.putObject("details").put("page_url", "https://example.com/notices?page=2");
    return command;
  }

  @Test
  void rejectsOtherServicesMessages() throws IOException {
    ObjectNode command = (ObjectNode) json.readTree(fixture("analysis.execute"));
    command.put("command_type", "collection.execute");
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(command)))
        .isInstanceOf(ContractViolationException.class);
  }

  @Test
  void initialAllowsOmittingOptionalFieldsAndEnforcesTimeLimit() throws IOException {
    ObjectNode command = (ObjectNode) json.readTree(fixture("analysis.execute"));
    ObjectNode payload = (ObjectNode) command.get("payload");
    payload.remove(
        java.util.List.of(
            "current_candidate", "recovery_baselines", "failure_context", "intermediate_refs"));
    var decoded = contract.readCommand(json.writeValueAsString(command));
    var typed = contract.commandPayload(decoded, Messages.AnalysisExecute.class);
    var outgoing =
        new CommandEnvelope<>(
            decoded.formatVersion(),
            decoded.commandType(),
            decoded.jobId(),
            decoded.parentJobId(),
            decoded.requestedAt(),
            decoded.expiresAt(),
            typed);
    contract.readCommand(contract.writeCommand(outgoing));
    payload.put("max_duration_seconds", 301);
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(command)))
        .isInstanceOf(ContractViolationException.class);
    payload.put("max_duration_seconds", 300);
    payload.putNull("recovery_baselines");
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(command)))
        .isInstanceOf(ContractViolationException.class);
  }

  @Test
  void recheckRequiresOneSourceCandidateAndFailureContext() throws IOException {
    ObjectNode command = (ObjectNode) json.readTree(fixture("analysis.execute"));
    ObjectNode payload = (ObjectNode) command.get("payload");
    payload.put("purpose", "RECHECK_SOURCE");
    payload.set(
        "current_candidate",
        json.readTree(fixture("analysis.completed")).get("payload").get("candidate"));
    ObjectNode context = payload.putObject("failure_context");
    context.put("source_job_id", "6a2f36b6-0524-4bdb-b66a-c6cf27aafdfc");
    context.put("scope", "SOURCE");
    context.put("source_id", "1c226765-48b1-4518-ad16-057e8998eb84");
    context.putNull("project_id");
    context.putNull("project_source_id");
    context.putArray("field_ids");
    ObjectNode error = context.putObject("error");
    error.put("code", "EMPTY_LIST");
    error.put("message", "목록이 비었습니다");
    error.putObject("details");
    error.put("retryable", true);
    var decoded = contract.readCommand(json.writeValueAsString(command));
    var typed = contract.commandPayload(decoded, Messages.AnalysisExecute.class);
    assertThat(typed.failureContext().error().stage()).isNull();
    assertThat(typed.failureContext().error().retryable()).isTrue();
    var outgoing =
        new CommandEnvelope<>(
            decoded.formatVersion(),
            decoded.commandType(),
            decoded.jobId(),
            decoded.parentJobId(),
            decoded.requestedAt(),
            decoded.expiresAt(),
            typed);
    assertThat(
            json.readTree(contract.writeCommand(outgoing))
                .get("payload")
                .get("failure_context")
                .get("error")
                .has("stage"))
        .isFalse();
    payload.putNull("failure_context");
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(command)))
        .isInstanceOf(ContractViolationException.class);
    payload.set("failure_context", context);
    ((tools.jackson.databind.node.ArrayNode) payload.get("sources"))
        .add(payload.get("sources").get(0).deepCopy());
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(command)))
        .isInstanceOf(ContractViolationException.class);
  }

  @Test
  void successfulResultsRequireStoredReferencesAndDoNotEmitSampleRevision() throws IOException {
    ObjectNode event = (ObjectNode) json.readTree(fixture("analysis.completed"));
    ObjectNode result = (ObjectNode) event.get("payload").get("source_results").get(0);
    JsonNode sample = result.get("sample_ref").deepCopy();
    JsonNode intermediate = result.get("intermediate_ref").deepCopy();
    result.putNull("sample_ref");
    assertThatThrownBy(() -> contract.readEvent(json.writeValueAsString(event)))
        .isInstanceOf(ContractViolationException.class);
    result.set("sample_ref", sample);
    result.putNull("intermediate_ref");
    assertThatThrownBy(() -> contract.readEvent(json.writeValueAsString(event)))
        .isInstanceOf(ContractViolationException.class);
    result.set("intermediate_ref", intermediate);
    var decoded = contract.readEvent(json.writeValueAsString(event));
    var typed = contract.eventPayload(decoded, Messages.AnalysisCompleted.class);
    var outgoing =
        new EventEnvelope<>(
            decoded.formatVersion(),
            decoded.eventType(),
            decoded.eventId(),
            decoded.jobId(),
            decoded.occurredAt(),
            typed);
    JsonNode encoded = json.readTree(contract.writeEvent(outgoing)).get("payload");
    assertThat(encoded.get("source_results").get(0).has("sample_revision")).isFalse();
    JsonNode pagination =
        encoded.get("candidate").get("source_configs").get(0).get("navigation").get("pagination");
    assertThat(pagination.has("url_template")).isFalse();
    assertThat(pagination.has("start")).isFalse();
    assertThat(pagination.has("step")).isFalse();
  }

  @Test
  void retryRequiresIntermediateRefsEvenIfEmpty() throws IOException {
    ObjectNode command = (ObjectNode) json.readTree(fixture("analysis.execute"));
    ObjectNode payload = (ObjectNode) command.get("payload");
    payload.put("purpose", "RETRY_SOURCE");
    payload.set(
        "current_candidate",
        json.readTree(fixture("analysis.completed")).get("payload").get("candidate"));
    contract.readCommand(json.writeValueAsString(command));
    payload.remove("intermediate_refs");
    assertThatThrownBy(() -> contract.readCommand(json.writeValueAsString(command)))
        .isInstanceOf(ContractViolationException.class);
  }

  @Test
  void optionalErrorFieldsMustHaveTheCorrectTypes() throws IOException {
    ObjectNode event = (ObjectNode) json.readTree(fixture("analysis.completed"));
    ObjectNode error = ((ObjectNode) event.get("payload")).putObject("error");
    error.put("code", "FAILED");
    error.put("message", "실패");
    error.putObject("details");
    contract.readEvent(json.writeValueAsString(event));
    error.putNull("stage");
    assertThatThrownBy(() -> contract.readEvent(json.writeValueAsString(event)))
        .isInstanceOf(ContractViolationException.class);
    error.remove("stage");
    error.put("retryable", "true");
    assertThatThrownBy(() -> contract.readEvent(json.writeValueAsString(event)))
        .isInstanceOf(ContractViolationException.class);
  }

  private String fixture(String name) throws IOException {
    try (var stream = getClass().getResourceAsStream("/contracts/" + name + ".json")) {
      assertThat(stream).isNotNull();
      return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
