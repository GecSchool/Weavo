package com.weavo.analysis.job.infrastructure.messaging;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * Validates the JSON boundary before data binding can collapse missing and explicit null values.
 */
public final class ContractValidator {
  private static final Pattern UUID_V4 =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
  private static final Pattern UTC_TIMESTAMP =
      Pattern.compile("\\d{4}-\\d{2}-\\d{2}[Tt]\\d{2}:\\d{2}:\\d{2}(?:\\.\\d+)?(?:[Zz]|\\+00:00)");
  private static final Set<String> PURPOSES =
      Set.of(
          "INITIAL",
          "RETRY_SOURCE",
          "ADD_SOURCE",
          "REVALIDATE",
          "REPAIR_NAVIGATION",
          "REPAIR_CONNECTOR",
          "RECHECK_SOURCE");
  private static final Set<String> ANALYSIS_STATUSES =
      Set.of("SUCCEEDED", "PARTIAL", "FAILED", "SKIPPED");
  private static final Set<String> VALIDATION_STATUSES = Set.of("PASSED", "FAILED", "UNVERIFIABLE");
  private static final Set<String> FIELD_TYPES =
      Set.of("string", "number", "boolean", "date", "datetime", "url");

  private ContractValidator() {}

  public static void validateCommand(JsonNode root) {
    object(root, "$", false);
    version(root, "$");
    String type = text(member(root, "command_type", "$", false), "$.command_type");
    uuid(member(root, "job_id", "$", false), "$.job_id", false);
    uuid(member(root, "parent_job_id", "$", true), "$.parent_job_id", true);
    timestamp(member(root, "requested_at", "$", false), "$.requested_at", false);
    timestamp(member(root, "expires_at", "$", false), "$.expires_at", false);
    JsonNode payload = member(root, "payload", "$", false);
    object(payload, "$.payload", false);
    switch (type) {
      case "analysis.execute" -> analysisExecute(payload, "$.payload");
      default -> invalid("$.command_type");
    }
  }

  public static void validateEvent(JsonNode root) {
    object(root, "$", false);
    version(root, "$");
    String type = text(member(root, "event_type", "$", false), "$.event_type");
    uuid(member(root, "event_id", "$", false), "$.event_id", false);
    uuid(member(root, "job_id", "$", false), "$.job_id", false);
    timestamp(member(root, "occurred_at", "$", false), "$.occurred_at", false);
    JsonNode payload = member(root, "payload", "$", false);
    object(payload, "$.payload", false);
    switch (type) {
      case "analysis.completed" -> analysisCompleted(payload, "$.payload");
      default -> invalid("$.event_type");
    }
  }

  private static void analysisExecute(JsonNode payload, String path) {
    uuid(member(payload, "analysis_id", path, false), path + ".analysis_id", false);
    String purpose = choice(member(payload, "purpose", path, false), path + ".purpose", PURPOSES);
    JsonNode projectId = member(payload, "project_id", path, true);
    uuid(projectId, path + ".project_id", purpose.equals("REPAIR_NAVIGATION"));
    positive(member(payload, "revision", path, false), path + ".revision", false);
    positiveInt(
        member(payload, "max_duration_seconds", path, false), path + ".max_duration_seconds");
    if (purpose.equals("INITIAL") && payload.get("max_duration_seconds").intValue() > 300) {
      invalid(path + ".max_duration_seconds");
    }

    JsonNode sources = payload.get("sources");
    boolean needsSources =
        Set.of("INITIAL", "RETRY_SOURCE", "ADD_SOURCE", "REVALIDATE", "RECHECK_SOURCE")
            .contains(purpose);
    if (sources == null) {
      if (needsSources) {
        invalid(path + ".sources");
      }
    } else {
      array(sources, path + ".sources");
      if ((needsSources && sources.isEmpty())
          || (purpose.equals("RECHECK_SOURCE") && sources.size() != 1)) {
        invalid(path + ".sources");
      }
    }
    for (int i = 0; sources != null && i < sources.size(); i++) {
      String itemPath = path + ".sources[" + i + "]";
      JsonNode source = object(sources.get(i), itemPath, false);
      uuid(
          member(source, "project_source_id", itemPath, false),
          itemPath + ".project_source_id",
          false);
      uuid(member(source, "source_id", itemPath, true), itemPath + ".source_id", true);
      httpUrl(member(source, "url", itemPath, false), itemPath + ".url");
    }
    JsonNode collectionPurpose = payload.get("collection_purpose");
    if (purpose.equals("INITIAL")) {
      text(collectionPurpose, path + ".collection_purpose");
    } else if (collectionPurpose != null && !collectionPurpose.isNull()) {
      text(collectionPurpose, path + ".collection_purpose");
    }

    boolean repair = purpose.equals("REPAIR_NAVIGATION") || purpose.equals("REPAIR_CONNECTOR");
    JsonNode candidate = payload.get("current_candidate");
    if ((repair
            || Set.of("RETRY_SOURCE", "ADD_SOURCE", "REVALIDATE", "RECHECK_SOURCE")
                .contains(purpose))
        && (candidate == null || candidate.isNull())) {
      invalid(path + ".current_candidate");
    }
    if (candidate != null && !candidate.isNull()) {
      candidate(candidate, path + ".current_candidate");
      if (Set.of("ADD_SOURCE", "REVALIDATE", "RECHECK_SOURCE").contains(purpose)
          && candidate.get("schema").isNull()) {
        invalid(path + ".current_candidate.schema");
      }
      if (purpose.equals("RECHECK_SOURCE") && candidate.get("source_configs").isEmpty()) {
        invalid(path + ".current_candidate.source_configs");
      }
    }
    JsonNode baselines = payload.get("recovery_baselines");
    if (baselines != null) {
      array(baselines, path + ".recovery_baselines");
    }
    for (int i = 0; baselines != null && i < baselines.size(); i++) {
      recoveryBaseline(baselines.get(i), path + ".recovery_baselines[" + i + "]");
    }
    JsonNode context = payload.get("failure_context");
    if (context != null && !context.isNull()) {
      failureContext(context, path + ".failure_context");
    }
    if (repair && (baselines == null || baselines.isEmpty())) {
      invalid(path + ".recovery_baselines");
    }
    if ((repair || purpose.equals("RECHECK_SOURCE")) && (context == null || context.isNull())) {
      invalid(path + ".failure_context");
    }
    JsonNode refs = payload.get("intermediate_refs");
    if (refs == null && purpose.equals("RETRY_SOURCE")) {
      invalid(path + ".intermediate_refs");
    }
    if (refs != null) {
      array(refs, path + ".intermediate_refs");
    }
    for (int i = 0; refs != null && i < refs.size(); i++) {
      String itemPath = path + ".intermediate_refs[" + i + "]";
      JsonNode item = object(refs.get(i), itemPath, false);
      uuid(
          member(item, "project_source_id", itemPath, false),
          itemPath + ".project_source_id",
          false);
      objectReference(
          member(item, "intermediate_ref", itemPath, false), itemPath + ".intermediate_ref", false);
    }
  }

  /** Checks relationships between the typed rule sets after validation of their JSON shape. */
  static void validateRepairTargets(Messages.AnalysisExecute command) {
    boolean shared = command.purpose().equals("REPAIR_NAVIGATION");
    if (!shared && !command.purpose().equals("REPAIR_CONNECTOR")) {
      return;
    }
    var configs = command.currentCandidate().sourceConfigs();
    var baselines = command.recoveryBaselines();
    var failure = command.failureContext();
    if (!failure.scope().equals(shared ? "SOURCE" : "PROJECT_SOURCE")) {
      invalid("$.payload.failure_context.scope");
    }
    if (configs.size() != baselines.size() || (!shared && baselines.size() != 1)) {
      invalid("$.payload.current_candidate.source_configs");
    }
    Map<String, Messages.SourceConfig> configsBySource = new HashMap<>();
    var navigation = configs.getFirst().navigation();
    for (var config : configs) {
      if (configsBySource.putIfAbsent(config.projectSourceId(), config) != null) {
        invalid("$.payload.current_candidate.source_configs");
      }
      if (shared && !navigation.hasSameSourceAs(config.navigation())) {
        invalid("$.payload.current_candidate.source_configs.navigation");
      }
    }
    for (var baseline : baselines) {
      var config = configsBySource.remove(baseline.projectSourceId());
      if (config == null || !baseline.connectorRevision().equals(config.connectorRevision())) {
        invalid("$.payload.recovery_baselines");
      }
      if (!shared
          && (!baseline.projectId().equals(command.projectId())
              || !baseline.projectId().equals(failure.projectId())
              || !baseline.projectSourceId().equals(failure.projectSourceId()))) {
        invalid("$.payload.failure_context");
      }
    }
  }

  private static void analysisCompleted(JsonNode payload, String path) {
    uuid(member(payload, "attempt_id", path, false), path + ".attempt_id", false);
    uuid(member(payload, "analysis_id", path, false), path + ".analysis_id", false);
    String purpose = choice(member(payload, "purpose", path, false), path + ".purpose", PURPOSES);
    uuid(
        member(payload, "project_id", path, true),
        path + ".project_id",
        purpose.equals("REPAIR_NAVIGATION"));
    positive(member(payload, "revision", path, false), path + ".revision", false);
    String status =
        choice(member(payload, "status", path, false), path + ".status", ANALYSIS_STATUSES);
    JsonNode sampleCount = payload.get("recommendation_sample_count");
    String sampleCountPath = path + ".recommendation_sample_count";
    nonnegative(sampleCount, sampleCountPath, true);
    if (sampleCount != null
        && !sampleCount.isNull()
        && (sampleCount.longValue() > 50
            || !Set.of("INITIAL", "RETRY_SOURCE").contains(purpose)
            || !Set.of("SUCCEEDED", "PARTIAL").contains(status))) {
      invalid(sampleCountPath);
    }
    bool(member(payload, "schema_change_required", path, false), path + ".schema_change_required");
    JsonNode candidate = member(payload, "candidate", path, true);
    if (!candidate.isNull()) {
      candidate(candidate, path + ".candidate");
    }
    JsonNode results =
        array(member(payload, "source_results", path, false), path + ".source_results");
    for (int i = 0; i < results.size(); i++) {
      String itemPath = path + ".source_results[" + i + "]";
      JsonNode result = object(results.get(i), itemPath, false);
      uuid(
          member(result, "project_source_id", itemPath, false),
          itemPath + ".project_source_id",
          false);
      String resultStatus =
          choice(
              member(result, "status", itemPath, false), itemPath + ".status", ANALYSIS_STATUSES);
      String validationStatus =
          choice(
              member(result, "validation_status", itemPath, false),
              itemPath + ".validation_status",
              VALIDATION_STATUSES);
      objectReference(
          member(result, "sample_ref", itemPath, true),
          itemPath + ".sample_ref",
          !validationStatus.equals("PASSED"));
      messageError(member(result, "error", itemPath, true), itemPath + ".error", true);
      objectReference(
          member(result, "intermediate_ref", itemPath, true),
          itemPath + ".intermediate_ref",
          !(Set.of("INITIAL", "RETRY_SOURCE").contains(purpose)
              && resultStatus.equals("SUCCEEDED")));
    }
    messageError(member(payload, "error", path, true), path + ".error", true);
  }

  private static void candidate(JsonNode candidate, String path) {
    object(candidate, path, false);
    schema(member(candidate, "schema", path, true), path + ".schema", true);
    JsonNode configs =
        array(member(candidate, "source_configs", path, false), path + ".source_configs");
    for (int i = 0; i < configs.size(); i++) {
      String itemPath = path + ".source_configs[" + i + "]";
      JsonNode config = object(configs.get(i), itemPath, false);
      uuid(
          member(config, "project_source_id", itemPath, false),
          itemPath + ".project_source_id",
          false);
      navigation(member(config, "navigation", itemPath, false), itemPath + ".navigation");
      positive(
          member(config, "connector_revision", itemPath, false),
          itemPath + ".connector_revision",
          false);
      fieldConnectors(
          member(config, "field_connectors", itemPath, false), itemPath + ".field_connectors");
    }
    JsonNode excluded =
        array(
            member(candidate, "excluded_project_source_ids", path, false),
            path + ".excluded_project_source_ids");
    for (int i = 0; i < excluded.size(); i++) {
      uuid(excluded.get(i), path + ".excluded_project_source_ids[" + i + "]", false);
    }
    JsonNode mappings =
        array(member(candidate, "candidate_mappings", path, false), path + ".candidate_mappings");
    for (int i = 0; i < mappings.size(); i++) {
      String itemPath = path + ".candidate_mappings[" + i + "]";
      JsonNode mapping = object(mappings.get(i), itemPath, false);
      uuid(
          member(mapping, "project_source_id", itemPath, false),
          itemPath + ".project_source_id",
          false);
      objectReference(
          member(mapping, "intermediate_ref", itemPath, false),
          itemPath + ".intermediate_ref",
          false);
      text(member(mapping, "candidate_key", itemPath, false), itemPath + ".candidate_key");
      uuid(member(mapping, "field_id", itemPath, false), itemPath + ".field_id", false);
    }
  }

  private static void recoveryBaseline(JsonNode baseline, String path) {
    object(baseline, path, false);
    uuid(member(baseline, "project_id", path, false), path + ".project_id", false);
    uuid(member(baseline, "project_source_id", path, false), path + ".project_source_id", false);
    positive(member(baseline, "schema_version", path, false), path + ".schema_version", false);
    positive(
        member(baseline, "connector_revision", path, false), path + ".connector_revision", false);
    JsonNode baselineSchema = member(baseline, "schema", path, false);
    schema(baselineSchema, path + ".schema", false);
    latestRecords(
        member(baseline, "latest_records", path, false), baselineSchema, path + ".latest_records");
    objectReference(
        member(baseline, "approved_sample_ref", path, true), path + ".approved_sample_ref", true);
    fieldConnectors(member(baseline, "field_connectors", path, false), path + ".field_connectors");
  }

  private static void failureContext(JsonNode context, String path) {
    object(context, path, false);
    uuid(member(context, "source_job_id", path, false), path + ".source_job_id", false);
    String scope =
        choice(
            member(context, "scope", path, false),
            path + ".scope",
            Set.of("SOURCE", "PROJECT_SOURCE"));
    uuid(member(context, "source_id", path, false), path + ".source_id", false);
    uuid(member(context, "project_id", path, true), path + ".project_id", scope.equals("SOURCE"));
    uuid(
        member(context, "project_source_id", path, true),
        path + ".project_source_id",
        scope.equals("SOURCE"));
    JsonNode ids = array(member(context, "field_ids", path, false), path + ".field_ids");
    for (int i = 0; i < ids.size(); i++) {
      uuid(ids.get(i), path + ".field_ids[" + i + "]", false);
    }
    messageError(member(context, "error", path, false), path + ".error", false);
  }

  private static void schema(JsonNode schema, String path, boolean nullable) {
    if (object(schema, path, nullable) == null) {
      return;
    }
    JsonNode fields = array(member(schema, "fields", path, false), path + ".fields");
    for (int i = 0; i < fields.size(); i++) {
      String itemPath = path + ".fields[" + i + "]";
      JsonNode field = object(fields.get(i), itemPath, false);
      uuid(member(field, "field_id", itemPath, false), itemPath + ".field_id", false);
      text(member(field, "api_name", itemPath, false), itemPath + ".api_name");
      text(member(field, "display_name", itemPath, false), itemPath + ".display_name");
      choice(member(field, "type", itemPath, false), itemPath + ".type", FIELD_TYPES);
      bool(member(field, "nullable", itemPath, false), itemPath + ".nullable");
      JsonNode description = field.get("description");
      if (description != null && !description.isNull() && !description.isTextual()) {
        invalid(itemPath + ".description");
      }
      JsonNode displayOrder = field.get("display_order");
      if (displayOrder != null) {
        nonnegative(displayOrder, itemPath + ".display_order", true);
        if (!displayOrder.isNull() && !displayOrder.canConvertToInt()) {
          invalid(itemPath + ".display_order");
        }
      }
      JsonNode reason = field.get("recommendation_reason");
      if (reason != null && !reason.isNull() && !reason.isTextual()) {
        invalid(itemPath + ".recommendation_reason");
      }
    }
  }

  private static void fieldConnectors(JsonNode connectors, String path) {
    array(connectors, path);
    for (int i = 0; i < connectors.size(); i++) {
      String itemPath = path + "[" + i + "]";
      JsonNode connector = object(connectors.get(i), itemPath, false);
      uuid(member(connector, "field_id", itemPath, false), itemPath + ".field_id", false);
      JsonNode extract = connector.get("extract");
      if (extract != null && !extract.isNull()) {
        object(extract, itemPath + ".extract", false);
        text(member(extract, "type", itemPath + ".extract", false), itemPath + ".extract.type");
        JsonNode attribute = member(extract, "attribute", itemPath + ".extract", true);
        if (!attribute.isNull()) {
          text(attribute, itemPath + ".extract.attribute");
        }
      }
      JsonNode detail = connector.get("detail_navigation");
      if (detail != null && !detail.isNull()) {
        object(detail, itemPath + ".detail_navigation", false);
        JsonNode open = member(detail, "open", itemPath + ".detail_navigation", false);
        JsonNode returnRule = member(detail, "return", itemPath + ".detail_navigation", false);
        optionalString(open, "url_attribute", itemPath + ".detail_navigation.open");
        optionalString(returnRule, "selector", itemPath + ".detail_navigation.return");
      }
      // Rule records validate location, selector and navigation combinations.
    }
  }

  private static void navigation(JsonNode navigation, String path) {
    object(navigation, path, false);
    JsonNode pagination = member(navigation, "pagination", path, false);
    object(pagination, path + ".pagination", false);
    optionalString(pagination, "url_template", path + ".pagination");
    optionalInteger(pagination, "start", path + ".pagination");
    optionalInteger(pagination, "step", path + ".pagination");
  }

  private static void optionalString(JsonNode parent, String name, String path) {
    object(parent, path, false);
    JsonNode value = parent.get(name);
    if (value != null && !value.isTextual()) {
      invalid(path + "." + name);
    }
  }

  private static void optionalInteger(JsonNode parent, String name, String path) {
    object(parent, path, false);
    JsonNode value = parent.get(name);
    if (value != null && (!value.isIntegralNumber() || !value.canConvertToInt())) {
      invalid(path + "." + name);
    }
  }

  private static void latestRecords(JsonNode records, JsonNode schema, String path) {
    array(records, path);
    if (records.size() > 3) {
      invalid(path);
    }
    for (int i = 0; i < records.size(); i++) {
      String itemPath = path + "[" + i + "]";
      JsonNode record = object(records.get(i), itemPath, false);
      JsonNode values =
          object(
              member(record, "field_values", itemPath, false), itemPath + ".field_values", false);
      for (JsonNode field : schema.get("fields")) {
        if (!values.has(field.get("field_id").asText())) {
          invalid(itemPath + ".field_values");
        }
      }
      for (var entry : values.properties()) {
        if (!UUID_V4.matcher(entry.getKey()).matches()) {
          invalid(itemPath + ".field_values.<key>");
        }
        JsonNode value = entry.getValue();
        if (!(value.isNull() || value.isTextual() || value.isNumber() || value.isBoolean())) {
          invalid(itemPath + ".field_values.<value>");
        }
        if ((value.isFloat() || value.isDouble()) && !Double.isFinite(value.doubleValue())) {
          invalid(itemPath + ".field_values.<value>");
        }
      }
    }
  }

  private static void objectReference(JsonNode ref, String path, boolean nullable) {
    if (object(ref, path, nullable) == null) {
      return;
    }
    text(member(ref, "bucket", path, false), path + ".bucket");
    text(member(ref, "key", path, false), path + ".key");
  }

  private static void messageError(JsonNode error, String path, boolean nullable) {
    if (object(error, path, nullable) == null) {
      return;
    }
    text(member(error, "code", path, false), path + ".code");
    JsonNode stage = error.get("stage");
    if (stage != null) {
      text(stage, path + ".stage");
    }
    text(member(error, "message", path, false), path + ".message");
    object(member(error, "details", path, false), path + ".details", false);
    JsonNode retryable = error.get("retryable");
    if (retryable != null) {
      bool(retryable, path + ".retryable");
    }
    JsonNode evidence = error.get("failure_evidence");
    if (evidence != null) {
      array(evidence, path + ".failure_evidence");
      for (int i = 0; i < evidence.size(); i++) {
        String itemPath = path + ".failure_evidence[" + i + "]";
        JsonNode item = object(evidence.get(i), itemPath, false);
        uuid(
            member(item, "project_source_id", itemPath, false),
            itemPath + ".project_source_id",
            false);
        uuid(member(item, "field_id", itemPath, false), itemPath + ".field_id", false);
        objectReference(member(item, "ref", itemPath, false), itemPath + ".ref", false);
        timestamp(member(item, "expires_at", itemPath, false), itemPath + ".expires_at", false);
      }
    }
  }

  private static void version(JsonNode root, String path) {
    JsonNode value = member(root, "format_version", path, false);
    if (!value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() != 1) {
      invalid(path + ".format_version");
    }
  }

  private static JsonNode member(JsonNode parent, String name, String path, boolean nullable) {
    object(parent, path, false);
    JsonNode value = parent.get(name);
    if (value == null || (value.isNull() && !nullable)) {
      invalid(path + "." + name);
    }
    return value;
  }

  private static JsonNode object(JsonNode node, String path, boolean nullable) {
    if (node == null || node.isNull()) {
      if (nullable) {
        return null;
      }
      invalid(path);
    }
    if (!node.isObject()) {
      invalid(path);
    }
    return node;
  }

  private static JsonNode array(JsonNode node, String path) {
    if (node == null || !node.isArray()) {
      invalid(path);
    }
    return node;
  }

  private static String text(JsonNode node, String path) {
    if (node == null || !node.isTextual() || node.textValue().isBlank()) {
      invalid(path);
    }
    return node.textValue();
  }

  private static String choice(JsonNode node, String path, Set<String> allowed) {
    String value = text(node, path);
    if (!allowed.contains(value)) {
      invalid(path);
    }
    return value;
  }

  private static void uuid(JsonNode node, String path, boolean nullable) {
    if (node == null || node.isNull()) {
      if (nullable) {
        return;
      }
      invalid(path);
    }
    if (!node.isTextual() || !UUID_V4.matcher(node.textValue()).matches()) {
      invalid(path);
    }
  }

  private static void timestamp(JsonNode node, String path, boolean nullable) {
    if (node == null || node.isNull()) {
      if (nullable) {
        return;
      }
      invalid(path);
    }
    if (!node.isTextual() || !UTC_TIMESTAMP.matcher(node.textValue()).matches()) {
      invalid(path);
    }
    String value = node.textValue().replace('t', 'T').replace('z', 'Z');
    int dot = value.indexOf('.');
    if (dot >= 0) {
      int zone = value.endsWith("Z") ? value.length() - 1 : value.length() - 6;
      if (zone - dot - 1 > 9) {
        value = value.substring(0, dot + 10) + value.substring(zone);
      }
    }
    try {
      if (!OffsetDateTime.parse(value).getOffset().equals(ZoneOffset.UTC)) {
        invalid(path);
      }
    } catch (DateTimeParseException exception) {
      throw ContractViolationException.fromRedactedCause(
          path + " has an invalid timestamp", exception);
    }
  }

  private static void httpUrl(JsonNode node, String path) {
    String value = text(node, path);
    final URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException exception) {
      throw ContractViolationException.fromRedactedCause(path + " has an invalid URL", exception);
    }
    if (!uri.isAbsolute()
        || uri.getHost() == null
        || !("http".equalsIgnoreCase(uri.getScheme())
            || "https".equalsIgnoreCase(uri.getScheme()))) {
      invalid(path);
    }
  }

  private static void positiveInt(JsonNode node, String path) {
    positive(node, path, false);
    if (!node.canConvertToInt()) {
      invalid(path);
    }
  }

  private static void positive(JsonNode node, String path, boolean nullable) {
    nonnegative(node, path, nullable);
    if (!node.isNull() && node.longValue() == 0) {
      invalid(path);
    }
  }

  private static void nonnegative(JsonNode node, String path, boolean nullable) {
    if (node == null || node.isNull()) {
      if (nullable) {
        return;
      }
      invalid(path);
    }
    if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 0) {
      invalid(path);
    }
  }

  private static void bool(JsonNode node, String path) {
    if (node == null || !node.isBoolean()) {
      invalid(path);
    }
  }

  private static void invalid(String path) {
    throw new ContractViolationException(path);
  }
}
