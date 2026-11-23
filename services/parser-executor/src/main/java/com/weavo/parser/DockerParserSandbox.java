package com.weavo.parser;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class DockerParserSandbox implements AutoCloseable {
  private static final Duration ITEM_LIMIT = Duration.ofSeconds(5);
  private static final int MAX_OUTPUT = 64 * 1024;
  private final String image;
  private final Path libraries;
  private final ExecutorService streams = Executors.newVirtualThreadPerTaskExecutor();

  DockerParserSandbox(String image) {
    if (!image.matches("[a-zA-Z0-9][a-zA-Z0-9./:_@-]*")) {
      throw new IllegalArgumentException("Invalid parser image reference");
    }
    this.image = image;
    try {
      var jar =
          Path.of(ParserChild.class.getProtectionDomain().getCodeSource().getLocation().toURI());
      if (!Files.isRegularFile(jar)
          || !jar.getParent().getFileName().toString().equals("lib")
          || jar.toString().contains(",")) {
        throw new IllegalStateException(
            "Start the parser gateway using its installDist distribution");
      }
      libraries = jar.getParent();
    } catch (URISyntaxException error) {
      throw new IllegalStateException("Cannot locate parser runner libraries", error);
    }
  }

  JsonNode execute(ParserServer.Item item, Duration remaining) {
    String name = "weavo-parser-" + UUID.randomUUID();
    long limit = Math.min(remaining.toNanos(), ITEM_LIMIT.toNanos());
    long end = System.nanoTime() + limit;
    Process process = null;
    Future<byte[]> output = null;
    Future<?> input = null;
    try {
      create(name, end);
      process =
          new ProcessBuilder("docker", "start", "--attach", "--interactive", name)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      var child = process;
      byte[] payload =
          JsonMapper.shared()
              .writeValueAsBytes(Map.of("code", item.code(), "values", item.values()));
      input =
          streams.submit(() -> {
            try (var stdin = child.getOutputStream()) {
              stdin.write(payload);
            }
            return null;
          });
      output =
          streams.submit(() -> {
            try (var stdout = child.getInputStream()) {
              return stdout.readNBytes(MAX_OUTPUT + 1);
            }
          });
      if (!process.waitFor(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS)) {
        stop(process, name);
        if (remaining.compareTo(ITEM_LIMIT) <= 0) {
          throw new Unavailable();
        }
        return failure(item.key(), "EXECUTION_LIMIT");
      }
      if (process.exitValue() == 125 || process.exitValue() == 126 || process.exitValue() == 127) {
        throw new Unavailable();
      }
      if (process.exitValue() == 137) {
        return failure(item.key(), "EXECUTION_LIMIT");
      }
      if (process.exitValue() != 0) {
        return failure(item.key(), "EXECUTION_FAILED");
      }
      input.get(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS);
      return decode(
          item.key(), output.get(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS));
    } catch (IOException | ExecutionException | TimeoutException error) {
      throw new Unavailable();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new Unavailable();
    } finally {
      if (process == null || process.isAlive()) {
        if (process != null) {
          process.destroyForcibly();
        }
        remove(name);
      }
      if (input != null && !input.isDone()) {
        input.cancel(true);
      }
      if (output != null && !output.isDone()) {
        output.cancel(true);
      }
    }
  }

  private void create(String name, long end) throws IOException, InterruptedException {
    var container =
        new ProcessBuilder(
                "docker",
                "create",
                "--rm",
                "--name",
                name,
                "--pull=never",
                "--log-driver=none",
                "--network=none",
                "--read-only",
                "--tmpfs",
                "/tmp:rw,noexec,nosuid,nodev,size=8m",
                "--cap-drop=ALL",
                "--security-opt=no-new-privileges",
                "--pids-limit=64",
                "--memory=256m",
                "--memory-swap=256m",
                "--cpus=1",
                "--user=65534:65534",
                "--env",
                "HTTP_PROXY=",
                "--env",
                "HTTPS_PROXY=",
                "--env",
                "ALL_PROXY=",
                "--env",
                "http_proxy=",
                "--env",
                "https_proxy=",
                "--env",
                "all_proxy=",
                "--env",
                "NO_PROXY=",
                "--env",
                "no_proxy=",
                "--mount",
                "type=bind,src=" + libraries + ",dst=/runner,readonly",
                "-i",
                "--entrypoint",
                "java",
                image,
                "-Xmx64m",
                "-XX:+UseSerialGC",
                "-cp",
                "/runner/*",
                "com.weavo.parser.ParserChild")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start();
    try {
      if (!container.waitFor(Math.max(1, end - System.nanoTime()), TimeUnit.NANOSECONDS)
          || container.exitValue() != 0) {
        throw new Unavailable();
      }
    } finally {
      if (container.isAlive()) {
        container.destroyForcibly();
      }
    }
  }

  private static JsonNode decode(String key, byte[] body) {
    if (body.length > 0 && body.length <= MAX_OUTPUT) {
      try {
        var result = JsonMapper.shared().readTree(body);
        String status = result.path("status").asText();
        var value = result.get("value");
        if ("SUCCEEDED".equals(status) && value != null && (value.isNull() || value.isTextual())) {
          var response = JsonMapper.shared().createObjectNode();
          response.put("item_key", key).put("status", status).set("value", value);
          return response;
        }
        String problem = result.path("failure").asText();
        if ("FAILED".equals(status)
            && List.of("COMPILATION_FAILED", "EXECUTION_FAILED").contains(problem)) {
          return failure(key, problem);
        }
      } catch (JacksonException ignored) {
        // The isolated code can write invalid bytes to its own output.
      }
    }
    return failure(key, "EXECUTION_FAILED");
  }

  private static JsonNode failure(String key, String reason) {
    return JsonMapper.shared()
        .createObjectNode()
        .put("item_key", key)
        .put("status", "FAILED")
        .put("failure", reason);
  }

  private static void stop(Process process, String name) {
    process.destroyForcibly();
    remove(name);
  }

  private static void remove(String name) {
    try {
      var cleanup =
          new ProcessBuilder("docker", "rm", "-f", name)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!cleanup.waitFor(2, TimeUnit.SECONDS)) {
        cleanup.destroyForcibly();
        throw new Unavailable();
      }
    } catch (IOException error) {
      throw new Unavailable();
    } catch (InterruptedException error) {
      Thread.currentThread().interrupt();
      throw new Unavailable();
    }
  }

  @Override
  public void close() {
    streams.shutdownNow();
  }

  static final class Unavailable extends RuntimeException {}
}
