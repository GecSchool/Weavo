package com.weavo.crawler.collection.infrastructure.messaging;

import java.net.URI;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.HashSet;
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
  private static final Set<String> COLLECTION_STATUSES =
      Set.of("SUCCEEDED", "PARTIAL", "FAILED", "CANCELLED");
  private static final Set<String> FIELD_TYPES =
      Set.of("string", "number", "boolean", "date", "datetime", "url");
  private static final Set<String> EXECUTION_REASONS =
      Set.of("INITIAL", "SCHEDULED", "MANUAL", "REVISION", "RECOVERY");
  private static final Set<String> STOP_REASONS =
      Set.of(
          "TIME_LIMIT",
          "END_OF_LIST",
          "NO_CHANGE",
          "NAVIGATION_FAILED",
          "ACCESS_BLOCKED",
          "TECHNICAL_FAILURE",
          "EMPTY_LIST",
          "CANCELLED");

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
      case "collection.execute" -> collectionExecute(payload, "$.payload");
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
      case "collection.completed" -> collectionCompleted(payload, "$.payload");
      case "job.issue.detected" -> issueDetected(payload, "$.payload");
      default -> invalid("$.event_type");
    }
  }

  private static void collectionExecute(JsonNode payload, String path) {
    uuid(member(payload, "source_id", path, false), path + ".source_id", false);
    choice(
        member(payload, "execution_reason", path, false),
        path + ".execution_reason",
        EXECUTION_REASONS);
    positiveInt(
        member(payload, "max_duration_seconds", path, false), path + ".max_duration_seconds");
    positive(
        member(payload, "navigation_revision", path, false), path + ".navigation_revision", false);
    navigation(member(payload, "navigation", path, false), path + ".navigation");
    JsonNode projects = array(member(payload, "projects", path, false), path + ".projects");
    if (projects.isEmpty()) {
      invalid(path + ".projects");
    }
    boolean hasFullCollection = false;
    Set<String> projectSourceIds = new HashSet<>();
    for (int i = 0; i < projects.size(); i++) {
      String itemPath = path + ".projects[" + i + "]";
      JsonNode project = object(projects.get(i), itemPath, false);
      uuid(member(project, "project_id", itemPath, false), itemPath + ".project_id", false);
      uuid(
          member(project, "project_source_id", itemPath, false),
          itemPath + ".project_source_id",
          false);
      if (!projectSourceIds.add(project.get("project_source_id").stringValue())) {
        invalid(itemPath + ".project_source_id");
      }
      positive(
          member(project, "schema_version", itemPath, false), itemPath + ".schema_version", false);
      positive(
          member(project, "connector_revision", itemPath, false),
          itemPath + ".connector_revision",
          false);
      String mode =
          choice(
              member(project, "collection_mode", itemPath, false),
              itemPath + ".collection_mode",
              Set.of("FULL", "INCREMENTAL"));
      hasFullCollection |= mode.equals("FULL");
      JsonNode projectSchema = member(project, "schema", itemPath, false);
      schema(projectSchema, itemPath + ".schema", false);
      var connectors = member(project, "field_connectors", itemPath, false);
      fieldConnectors(connectors, itemPath + ".field_connectors");
      requireCompleteConnectors(projectSchema.get("fields"), connectors, itemPath);
      JsonNode latest = member(project, "latest_records", itemPath, false);
      latestRecords(latest, projectSchema, itemPath + ".latest_records");
      if ((mode.equals("FULL") && !latest.isEmpty())
          || (mode.equals("INCREMENTAL") && latest.isEmpty())) {
        invalid(itemPath + ".latest_records");
      }
    }
    int maximumDuration = hasFullCollection ? 300 : 60;
    if (payload.get("max_duration_seconds").intValue() > maximumDuration) {
      invalid(path + ".max_duration_seconds");
    }
  }

  private static void requireCompleteConnectors(
      JsonNode fields, JsonNode connectors, String itemPath) {
    Set<String> unmatched = new HashSet<>();
    fields.forEach(field -> unmatched.add(field.get("field_id").stringValue()));
    for (JsonNode connector : connectors) {
      if (!unmatched.remove(connector.get("field_id").stringValue())) {
        invalid(itemPath + ".field_connectors");
      }
    }
    if (!unmatched.isEmpty()) {
      invalid(itemPath + ".field_connectors");
    }
  }

  private static void collectionCompleted(JsonNode payload, String path) {
    uuid(member(payload, "attempt_id", path, false), path + ".attempt_id", false);
    uuid(member(payload, "source_id", path, false), path + ".source_id", false);
    positive(
        member(payload, "navigation_revision", path, false), path + ".navigation_revision", false);
    choice(member(payload, "status", path, false), path + ".status", COLLECTION_STATUSES);
    choice(member(payload, "stop_reason", path, false), path + ".stop_reason", STOP_REASONS);
    timestamp(member(payload, "started_at", path, false), path + ".started_at", false);
    timestamp(member(payload, "finished_at", path, false), path + ".finished_at", false);
    nonnegative(member(payload, "list_item_count", path, true), path + ".list_item_count", true);
    JsonNode snapshotRef = member(payload, "snapshot_ref", path, true);
    objectReference(snapshotRef, path + ".snapshot_ref", true);
    JsonNode results =
        array(member(payload, "project_results", path, false), path + ".project_results");
    for (int i = 0; i < results.size(); i++) {
      String itemPath = path + ".project_results[" + i + "]";
      JsonNode result = object(results.get(i), itemPath, false);
      uuid(member(result, "project_id", itemPath, false), itemPath + ".project_id", false);
      uuid(
          member(result, "project_source_id", itemPath, false),
          itemPath + ".project_source_id",
          false);
      positive(
          member(result, "schema_version", itemPath, false), itemPath + ".schema_version", false);
      positive(
          member(result, "connector_revision", itemPath, false),
          itemPath + ".connector_revision",
          false);
      choice(member(result, "status", itemPath, false), itemPath + ".status", COLLECTION_STATUSES);
      JsonNode canIngest = member(result, "can_ingest", itemPath, false);
      bool(canIngest, itemPath + ".can_ingest");
      if (canIngest.booleanValue() && snapshotRef.isNull()) {
        invalid(itemPath + ".can_ingest");
      }
      nonnegative(
          member(result, "normal_extracted_record_count", itemPath, true),
          itemPath + ".normal_extracted_record_count",
          true);
      messageError(member(result, "error", itemPath, true), itemPath + ".error", true);
    }
    messageError(member(payload, "error", path, true), path + ".error", true);
  }

  private static void issueDetected(JsonNode payload, String path) {
    choice(
        member(payload, "reporting_service", path, false),
        path + ".reporting_service",
        Set.of("CRAWLER"));
    uuid(member(payload, "attempt_id", path, false), path + ".attempt_id", false);
    String scope =
        choice(
            member(payload, "scope", path, false),
            path + ".scope",
            Set.of("SOURCE", "PROJECT_SOURCE"));
    uuid(member(payload, "source_id", path, false), path + ".source_id", false);
    JsonNode project = member(payload, "project_id", path, true);
    JsonNode projectSource = member(payload, "project_source_id", path, true);
    JsonNode connector = member(payload, "connector_revision", path, true);
    boolean sourceScope = scope.equals("SOURCE");
    uuid(project, path + ".project_id", sourceScope);
    uuid(projectSource, path + ".project_source_id", sourceScope);
    positive(connector, path + ".connector_revision", sourceScope);
    if (sourceScope && (!project.isNull() || !projectSource.isNull() || !connector.isNull())) {
      invalid(path + ".scope");
    }
    JsonNode fields = array(member(payload, "field_ids", path, false), path + ".field_ids");
    if (sourceScope && !fields.isEmpty()) {
      invalid(path + ".field_ids");
    }
    for (int i = 0; i < fields.size(); i++) {
      uuid(fields.get(i), path + ".field_ids[" + i + "]", false);
    }
    positive(member(payload, "schema_version", path, true), path + ".schema_version", sourceScope);
    positive(
        member(payload, "navigation_revision", path, false), path + ".navigation_revision", false);
    messageError(member(payload, "error", path, false), path + ".error", false);
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
