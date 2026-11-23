package com.weavo.extraction.infrastructure;

import com.weavo.extraction.FieldValueProcessor.ParserUnavailable;
import com.weavo.extraction.JavaFieldParser;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Sends parser inputs to the authenticated executor without running generated code in the caller.
 */
public final class IsolatedParserClient {
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(10);
  private static final String PARSER_ROUTE = "/internal/v1/parser:execute";

  private final HttpClient http;
  private final URI endpoint;
  private final String token;

  public IsolatedParserClient(URI service, String token) {
    if (service.getHost() == null
        || service.getRawUserInfo() != null
        || !List.of("http", "https").contains(service.getScheme())
        || token == null
        || token.isBlank()) {
      throw new IllegalArgumentException(
          "The parser service address and Bearer token are required");
    }
    this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).build();
    this.endpoint = service.resolve(PARSER_ROUTE);
    this.token = token;
  }

  public JavaFieldParser.Result parse(
      String code, List<String> values, Runnable beforeCall, Supplier<Duration> remaining) {
    String key = UUID.randomUUID().toString();
    for (int call = 0; call < 2; call++) {
      beforeCall.run();
      long millis = Math.min(remaining.get().toMillis(), CALL_TIMEOUT.toMillis());
      var request =
          HttpRequest.newBuilder(endpoint)
              .timeout(Duration.ofMillis(Math.max(1, millis)))
              .header("Content-Type", "application/json")
              .header("Authorization", "Bearer " + token)
              .POST(
                  HttpRequest.BodyPublishers.ofString(
                      JsonMapper.shared()
                          .writeValueAsString(
                              Map.of(
                                  "timeout_ms", Math.max(1, millis),
                                  "items",
                                      List.of(
                                          Map.of(
                                              "item_key",
                                              key,
                                              "code",
                                              code,
                                              "raw_values",
                                              values))))))
              .build();
      try {
        var response = send(request, remaining);
        remaining.get();
        if (response.statusCode() == 200) {
          return result(response.body(), key);
        }
        if (response.statusCode() != 503 || call == 1) {
          throw new ParserUnavailable("HTTP_" + response.statusCode());
        }
      } catch (ExecutionException failure) {
        if (call == 1) {
          throw new ParserUnavailable("HTTP_UNAVAILABLE");
        }
      }
    }
    throw new ParserUnavailable("HTTP_UNAVAILABLE");
  }

  private HttpResponse<String> send(HttpRequest request, Supplier<Duration> remaining)
      throws ExecutionException {
    var pending = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
    try {
      while (true) {
        long wait = Math.max(1, Math.min(remaining.get().toMillis(), 100));
        try {
          return pending.get(wait, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
          // The calling worker rechecks its lease and deadline during the wait.
        }
      }
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      throw new CancellationException("Parser request interrupted");
    } finally {
      if (!pending.isDone()) {
        pending.cancel(true);
      }
    }
  }

  private static JavaFieldParser.Result result(String body, String key) {
    try {
      var results = JsonMapper.shared().readTree(body).path("results");
      if (!results.isArray()
          || results.size() != 1
          || !key.equals(results.get(0).path("item_key").asText())) {
        throw new ParserUnavailable("INVALID_PARSER_RESPONSE");
      }
      var row = results.get(0);
      var value = row.get("value");
      return switch (row.path("status").asText()) {
        case "SUCCEEDED" -> {
          if (row.hasNonNull("failure")
              || value == null
              || !(value.isTextual() || value.isNull())) {
            throw new ParserUnavailable("INVALID_PARSER_RESPONSE");
          }
          yield new JavaFieldParser.Result(value.isNull() ? null : value.asText(), null);
        }
        case "FAILED" -> {
          if (row.hasNonNull("value")) {
            throw new ParserUnavailable("INVALID_PARSER_RESPONSE");
          }
          var stage =
              switch (row.path("failure").asText()) {
                case "COMPILATION_FAILED" -> JavaFieldParser.Stage.COMPILATION;
                case "EXECUTION_FAILED" -> JavaFieldParser.Stage.EXECUTION;
                case "EXECUTION_LIMIT" -> JavaFieldParser.Stage.EXECUTION_LIMIT;
                default -> throw new ParserUnavailable("INVALID_PARSER_RESPONSE");
              };
          yield new JavaFieldParser.Result(
              null, new JavaFieldParser.Failure(stage, List.of(), null));
        }
        default -> throw new ParserUnavailable("INVALID_PARSER_RESPONSE");
      };
    } catch (JacksonException failure) {
      throw new ParserUnavailable("INVALID_PARSER_RESPONSE");
    }
  }
}
