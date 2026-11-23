package com.weavo.parser;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Local HTTP gateway. The Docker daemon runs only the sandboxed parser child. */
public final class ParserServer {
  private static final String ROUTE = "/internal/v1/parser:execute";
  private static final int MAX_BODY_BYTES = 512 * 1024;
  private static final int MAX_ITEMS = 50;
  private static final int MAX_TIMEOUT_MILLIS = 300_000;
  private static final JsonMapper JSON = JsonMapper.shared();

  private final List<byte[]> bearerTokens;
  private final DockerParserSandbox sandbox;
  private final Semaphore capacity = new Semaphore(2);

  private ParserServer(List<byte[]> bearerTokens, DockerParserSandbox sandbox) {
    this.bearerTokens = bearerTokens;
    this.sandbox = sandbox;
  }

  public static void main(String[] args) throws IOException {
    String raw = System.getenv("WEAVO_PARSER_ALLOWED_TOKENS");
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException("WEAVO_PARSER_ALLOWED_TOKENS is required");
    }
    var tokens =
        List.of(raw.split(",")).stream()
            .map(String::strip)
            .filter(t -> !t.isEmpty())
            .map(t -> ("Bearer " + t).getBytes(StandardCharsets.UTF_8))
            .toList();
    if (tokens.isEmpty()) {
      throw new IllegalArgumentException("At least one parser service token is required");
    }
    int port =
        Integer.parseInt(Objects.requireNonNullElse(System.getenv("WEAVO_PARSER_PORT"), "8095"));
    String image =
        Objects.requireNonNullElse(System.getenv("WEAVO_PARSER_IMAGE"), "eclipse-temurin:21-jdk");
    try (var sandbox = new DockerParserSandbox(image)) {
      var application = new ParserServer(tokens, sandbox);
      var server =
          HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 16);
      try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
        server.createContext(ROUTE, application::handle);
        server.setExecutor(executor);
        server.start();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> server.stop(0)));
        try {
          new java.util.concurrent.CountDownLatch(1).await();
        } catch (InterruptedException interrupted) {
          Thread.currentThread().interrupt();
        } finally {
          server.stop(0);
        }
      }
    }
  }

  private void handle(HttpExchange exchange) throws IOException {
    try (exchange) {
      if (!ROUTE.equals(exchange.getRequestURI().getPath())
          || !"POST".equals(exchange.getRequestMethod())) {
        reply(exchange, 404, Map.of("error", "NOT_FOUND"));
      } else if (!authorized(exchange.getRequestHeaders().get("Authorization"))) {
        reply(exchange, 401, Map.of("error", "UNAUTHORIZED"));
      } else if (!capacity.tryAcquire()) {
        reply(exchange, 503, Map.of("error", "EXECUTOR_BUSY"));
      } else {
        try {
          var bytes = exchange.getRequestBody().readNBytes(MAX_BODY_BYTES + 1);
          var request = parseRequest(bytes);
          reply(exchange, 200, Map.of("results", execute(request)));
        } catch (InvalidRequest | JacksonException failure) {
          reply(exchange, 400, Map.of("error", "INVALID_REQUEST"));
        } catch (DockerParserSandbox.Unavailable failure) {
          reply(exchange, 503, Map.of("error", "EXECUTOR_UNAVAILABLE"));
        } finally {
          capacity.release();
        }
      }
    }
  }

  private boolean authorized(List<String> headers) {
    if (headers == null || headers.size() != 1) {
      return false;
    }
    byte[] provided = headers.getFirst().getBytes(StandardCharsets.UTF_8);
    return bearerTokens.stream().anyMatch(stored -> MessageDigest.isEqual(stored, provided));
  }

  private static Request parseRequest(byte[] bytes) {
    if (bytes.length == 0 || bytes.length > MAX_BODY_BYTES) {
      throw new InvalidRequest();
    }
    var root = JSON.readTree(bytes);
    var timeout = root.path("timeout_ms");
    var items = root.path("items");
    if (!timeout.isIntegralNumber()
        || !timeout.canConvertToInt()
        || timeout.asInt() < 1
        || timeout.asInt() > MAX_TIMEOUT_MILLIS
        || !items.isArray()
        || items.isEmpty()
        || items.size() > MAX_ITEMS) {
      throw new InvalidRequest();
    }
    Set<String> keys = new HashSet<>();
    var batch = new ArrayList<Item>();
    for (var item : items) {
      var key = item.path("item_key");
      var code = item.path("code");
      var raw = item.path("raw_values");
      if (!key.isTextual()
          || key.asText().isBlank()
          || !keys.add(key.asText())
          || !code.isTextual()
          || code.asText().isBlank()
          || !raw.isArray()) {
        throw new InvalidRequest();
      }
      var values = new ArrayList<String>();
      for (var value : raw) {
        if (!value.isTextual()) {
          throw new InvalidRequest();
        }
        values.add(value.asText());
      }
      batch.add(new Item(key.asText(), code.asText(), values));
    }
    return new Request(Duration.ofMillis(timeout.asInt()), batch);
  }

  private List<JsonNode> execute(Request request) {
    long end = System.nanoTime() + request.timeout().toNanos();
    var results = new ArrayList<JsonNode>();
    for (var item : request.items()) {
      Duration remaining = Duration.ofNanos(end - System.nanoTime());
      if (remaining.isZero() || remaining.isNegative()) {
        throw new DockerParserSandbox.Unavailable();
      }
      results.add(sandbox.execute(item, remaining));
    }
    if (System.nanoTime() >= end) {
      throw new DockerParserSandbox.Unavailable();
    }
    return results;
  }

  private static void reply(HttpExchange exchange, int status, Map<String, ?> body)
      throws IOException {
    byte[] bytes = JSON.writeValueAsBytes(body);
    exchange.getResponseHeaders().set("Content-Type", "application/json");
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  record Item(String key, String code, List<String> values) {}

  private record Request(Duration timeout, List<Item> items) {}

  private static final class InvalidRequest extends RuntimeException {}
}
