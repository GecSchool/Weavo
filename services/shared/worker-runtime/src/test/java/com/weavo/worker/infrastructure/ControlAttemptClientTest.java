package com.weavo.worker.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.sun.net.httpserver.HttpServer;
import com.weavo.worker.infrastructure.ControlAttemptClient.ControlFailure;
import com.weavo.worker.infrastructure.ControlAttemptClient.Status;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ControlAttemptClientTest {
  @Test
  void postsClaimAndHeartbeatWithTheInternalBearerAndExactBody() throws Exception {
    UUID jobId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    String prefix = "/internal/v1/jobs/" + jobId;
    String leaseExpiry = "2026-11-20T12:00:30Z";
    var receivedClaim = new AtomicReference<Request>();
    var receivedHeartbeat = new AtomicReference<Request>();
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    respond(
        server,
        prefix + "/attempts:claim",
        receivedClaim,
        200,
        "{\"status\":\"ACQUIRED\",\"lease_expires_at\":\"" + leaseExpiry + "\"}");
    respond(
        server,
        prefix + "/attempts/" + attemptId + ":heartbeat",
        receivedHeartbeat,
        200,
        "{\"lease_expires_at\":\"" + leaseExpiry + "\"}");
    server.start();

    try (var client = client(server)) {
      var result = client.claim(jobId, attemptId);
      assertThat(result.status()).isEqualTo(Status.ACQUIRED);
      assertThat(result.leaseExpiresAt()).isEqualTo(Instant.parse(leaseExpiry));
      assertThat(client.heartbeat(jobId, attemptId)).isEqualTo(Instant.parse(leaseExpiry));
      assertThat(receivedClaim.get())
          .isEqualTo(
              new Request(
                  "POST", "Bearer test-worker-identity", "{\"attempt_id\":\"" + attemptId + "\"}"));
      assertThat(receivedHeartbeat.get())
          .isEqualTo(new Request("POST", "Bearer test-worker-identity", "{}"));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void rejectsUnauthorizedResponsesAndTreatsHeartbeatConflictAsRevocation() throws Exception {
    UUID jobId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    String prefix = "/internal/v1/jobs/" + jobId;
    var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    respond(server, prefix + "/attempts:claim", new AtomicReference<>(), 401, "private response");
    respond(
        server,
        prefix + "/attempts/" + attemptId + ":heartbeat",
        new AtomicReference<>(),
        409,
        "{}");
    server.start();

    try (var client = client(server)) {
      assertThatExceptionOfType(ControlFailure.class)
          .isThrownBy(() -> client.claim(jobId, attemptId))
          .withMessage("Control responded with HTTP 401")
          .satisfies(failure -> assertThat(failure.retryable()).isFalse());
      assertThat(client.heartbeat(jobId, attemptId)).isNull();
    } finally {
      server.stop(0);
    }
  }

  private static ControlAttemptClient client(HttpServer server) {
    return new ControlAttemptClient(
        "http://127.0.0.1:" + server.getAddress().getPort(),
        "test-worker-identity",
        Duration.ofSeconds(2),
        true);
  }

  private static void respond(
      HttpServer server,
      String path,
      AtomicReference<Request> received,
      int status,
      String response) {
    server.createContext(
        path,
        exchange -> {
          try (exchange) {
            received.set(
                new Request(
                    exchange.getRequestMethod(),
                    exchange.getRequestHeaders().getFirst("Authorization"),
                    new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
          }
        });
  }

  private record Request(String method, String authorization, String body) {}
}
