package com.weavo.worker.infrastructure;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Service-authenticated client for the Control attempt ledger. */
public final class ControlAttemptClient implements AutoCloseable {
  private final JsonMapper json =
      JsonMapper.builder().enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
  private final URI endpoint;
  private final String serviceToken;
  private final Duration timeout;
  private final HttpClient http;

  public ControlAttemptClient(
      String endpoint, String serviceToken, Duration timeout, boolean local) {
    this.endpoint = URI.create(endpoint);
    String scheme = this.endpoint.getScheme();
    if (this.endpoint.getHost() == null
        || this.endpoint.getUserInfo() != null
        || this.endpoint.getQuery() != null
        || this.endpoint.getFragment() != null
        || !("https".equals(scheme) || (local && "http".equals(scheme)))) {
      throw new IllegalArgumentException("WEAVO_CONTROL_URL must be an absolute service endpoint");
    }
    if (serviceToken.isBlank() || serviceToken.chars().anyMatch(Character::isWhitespace)) {
      throw new IllegalArgumentException("A service bearer token is required");
    }
    this.serviceToken = serviceToken;
    this.timeout = timeout;
    this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
  }

  public Claim claim(UUID jobId, UUID attemptId) throws InterruptedException {
    URI uri = jobUri(jobId, "/attempts:claim");
    String body = "{\"attempt_id\":\"" + attemptId + "\"}";
    JsonNode response = responseJson(post(uri, body));
    JsonNode statusNode = response.get("status");
    JsonNode expiryNode = response.get("lease_expires_at");
    if (statusNode == null || !statusNode.isTextual() || expiryNode == null) {
      throw new ControlFailure("Invalid claim response", false);
    }
    try {
      var status = Status.valueOf(statusNode.stringValue());
      return switch (status) {
        case ACQUIRED, BUSY -> new Claim(status, parseExpiry(expiryNode));
        case TERMINAL, EXPIRED -> {
          if (!expiryNode.isNull()) {
            throw new ControlFailure("Invalid terminal claim response", false);
          }
          yield new Claim(status, null);
        }
      };
    } catch (IllegalArgumentException exception) {
      throw new ControlFailure("Unknown claim status", false);
    }
  }

  /** A null result represents Control's explicit 409 ownership rejection. */
  public Instant heartbeat(UUID jobId, UUID attemptId) throws InterruptedException {
    var response = post(jobUri(jobId, "/attempts/" + attemptId + ":heartbeat"), "{}");
    return response.statusCode() == 409
        ? null
        : parseExpiry(responseJson(response).get("lease_expires_at"));
  }

  @Override
  public void close() {
    http.shutdownNow();
  }

  private URI jobUri(UUID jobId, String path) {
    return endpoint.resolve("/internal/v1/jobs/" + jobId + path);
  }

  private HttpResponse<String> post(URI uri, String body) throws InterruptedException {
    var request =
        HttpRequest.newBuilder(uri)
            .header("Authorization", "Bearer " + serviceToken)
            .header("Content-Type", "application/json")
            .timeout(timeout)
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
    try {
      return http.send(request, HttpResponse.BodyHandlers.ofString());
    } catch (IOException exception) {
      throw new ControlFailure("Control request failed", true, exception);
    }
  }

  private JsonNode responseJson(HttpResponse<String> response) {
    int status = response.statusCode();
    if (status != 200) {
      throw new ControlFailure("Control responded with HTTP " + status, status >= 500);
    }
    try {
      JsonNode body = json.readTree(response.body());
      if (!body.isObject()) {
        throw new ControlFailure("Invalid Control response body", false);
      }
      return body;
    } catch (JacksonException exception) {
      // Response bodies and reflected values do not belong in worker diagnostics.
      throw new ControlFailure(
          "Invalid Control JSON (" + exception.getClass().getSimpleName() + ")", false);
    }
  }

  private static Instant parseExpiry(JsonNode value) {
    if (value == null || !value.isTextual()) {
      throw new ControlFailure("Missing Control lease expiry", false);
    }
    try {
      return Instant.parse(value.stringValue());
    } catch (DateTimeParseException exception) {
      throw new ControlFailure("Invalid Control lease expiry", false);
    }
  }

  public enum Status {
    ACQUIRED,
    BUSY,
    TERMINAL,
    EXPIRED
  }

  public record Claim(Status status, Instant leaseExpiresAt) {}

  public static final class ControlFailure extends RuntimeException {
    private final boolean retryable;

    public ControlFailure(String message, boolean retryable) {
      this(message, retryable, null);
    }

    private ControlFailure(String message, boolean retryable, Throwable cause) {
      super(message, cause);
      this.retryable = retryable;
    }

    public boolean retryable() {
      return retryable;
    }
  }
}
