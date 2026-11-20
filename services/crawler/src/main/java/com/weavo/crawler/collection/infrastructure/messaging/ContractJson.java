package com.weavo.crawler.collection.infrastructure.messaging;

import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.exc.ValueInstantiationException;
import tools.jackson.databind.json.JsonMapper;

/** JSON boundary for worker messages; it does not consume messages or decide job state. */
public final class ContractJson {
  private final ObjectMapper mapper;

  public ContractJson() {
    mapper =
        JsonMapper.builder()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
  }

  /**
   * Retains the JSON payload, including explicitly null and omitted optional fields. Use {@link
   * #commandPayload} for the corresponding typed view.
   */
  public CommandEnvelope<JsonNode> readCommand(String json) {
    JsonNode root = readTree(json);
    ContractValidator.validateCommand(root);
    JsonNode payload = root.get("payload");
    checkPayload(payload, commandPayloadType(root.get("command_type").stringValue()));
    return new CommandEnvelope<>(
        root.get("format_version").intValue(),
        root.get("command_type").stringValue(),
        root.get("job_id").stringValue(),
        nullableText(root.get("parent_job_id")),
        root.get("requested_at").stringValue(),
        root.get("expires_at").stringValue(),
        payload);
  }

  /**
   * Retains the payload, but not unknown envelope fields. Preserve the entire original message for
   * retries.
   */
  public EventEnvelope<JsonNode> readEvent(String json) {
    JsonNode root = readTree(json);
    ContractValidator.validateEvent(root);
    JsonNode payload = root.get("payload");
    checkPayload(payload, eventPayloadType(root.get("event_type").stringValue()));
    return new EventEnvelope<>(
        root.get("format_version").intValue(),
        root.get("event_type").stringValue(),
        root.get("event_id").stringValue(),
        root.get("job_id").stringValue(),
        root.get("occurred_at").stringValue(),
        payload);
  }

  /** Creates a new command from a typed payload, or re-emits an existing raw payload. */
  public String writeCommand(CommandEnvelope<?> command) {
    String json = write(Objects.requireNonNull(command, "command"));
    readCommand(json);
    return json;
  }

  /** Creates an event JSON representation; do not use it to reconstruct a previously sent event. */
  public String writeEvent(EventEnvelope<?> event) {
    String json = write(Objects.requireNonNull(event, "event"));
    readEvent(json);
    return json;
  }

  /** The returned typed view is not used to regenerate an existing event's raw payload. */
  public <T> T commandPayload(CommandEnvelope<JsonNode> command, Class<T> type) {
    Objects.requireNonNull(command, "command");
    checkExpectedType(type, commandPayloadType(command.commandType()));
    ContractValidator.validateCommand(mapper.valueToTree(command));
    return convert(command.payload(), type);
  }

  public <T> T eventPayload(EventEnvelope<JsonNode> event, Class<T> type) {
    Objects.requireNonNull(event, "event");
    checkExpectedType(type, eventPayloadType(event.eventType()));
    ContractValidator.validateEvent(mapper.valueToTree(event));
    return convert(event.payload(), type);
  }

  private JsonNode readTree(String json) {
    try {
      return mapper.readTree(Objects.requireNonNull(json, "json"));
    } catch (JacksonException exception) {
      var location = exception.getLocation();
      String message = "Invalid JSON message";
      if (location != null && location.getLineNr() > 0 && location.getColumnNr() > 0) {
        message += " at line " + location.getLineNr() + ", column " + location.getColumnNr();
      }
      throw ContractViolationException.fromRedactedCause(message, exception);
    }
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw ContractViolationException.fromRedactedCause(
          "Unable to serialize contract message", exception);
    }
  }

  private void checkPayload(JsonNode payload, Class<?> type) {
    convert(payload, type);
  }

  private <T> T convert(JsonNode payload, Class<T> type) {
    try {
      return mapper.treeToValue(payload, type);
    } catch (ValueInstantiationException exception) {
      throw ContractViolationException.fromRedactedCause(
          "$.payload violates " + exception.getType().getRawClass().getSimpleName() + " rules",
          Objects.requireNonNullElse(exception.getCause(), exception));
    } catch (JacksonException | IllegalArgumentException exception) {
      throw ContractViolationException.fromRedactedCause(
          "$.payload has an invalid shape", exception);
    }
  }

  private static void checkExpectedType(Class<?> actual, Class<?> expected) {
    if (!expected.equals(actual)) {
      throw new ContractViolationException("Payload type does not match message type");
    }
  }

  private static Class<?> commandPayloadType(String type) {
    return switch (type) {
      case "collection.execute" -> Messages.CollectionExecute.class;
      default -> throw new ContractViolationException("Unsupported command_type");
    };
  }

  private static Class<?> eventPayloadType(String type) {
    return switch (type) {
      case "collection.completed" -> Messages.CollectionCompleted.class;
      case "job.issue.detected" -> Messages.JobIssueDetected.class;
      default -> throw new ContractViolationException("Unsupported event_type");
    };
  }

  private static String nullableText(JsonNode value) {
    return value.isNull() ? null : value.stringValue();
  }
}
