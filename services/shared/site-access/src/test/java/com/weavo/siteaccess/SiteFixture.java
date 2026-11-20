package com.weavo.siteaccess;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.function.BooleanSupplier;

final class SiteFixture implements AutoCloseable {
  private final java.util.concurrent.ExecutorService executor =
      Executors.newVirtualThreadPerTaskExecutor();
  private final HttpServer server;
  private final InetAddress local;
  final ConcurrentLinkedQueue<String> requests = new ConcurrentLinkedQueue<>();
  final ConcurrentLinkedQueue<String> userAgents = new ConcurrentLinkedQueue<>();

  SiteFixture() throws IOException {
    local = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
    server = HttpServer.create(new InetSocketAddress(local, 0), 0);
    server.setExecutor(executor);
    route("/robots.txt", exchange -> reply(exchange, 404, "text/plain", ""));
    server.start();
  }

  void robots(String rules) {
    server.removeContext("/robots.txt");
    route("/robots.txt", exchange -> reply(exchange, 200, "text/plain", rules));
  }

  void route(String pattern, HttpHandler handler) {
    server.createContext(
        pattern,
        exchange -> {
          requests.add(exchange.getRequestURI().toString());
          userAgents.add(exchange.getRequestHeaders().getFirst("User-Agent"));
          try (exchange) {
            handler.handle(exchange);
          }
        });
  }

  String url(String path) {
    return url("site.example", path);
  }

  String url(String host, String path) {
    return "http://" + host + ":" + server.getAddress().getPort() + path;
  }

  SiteAccess access() {
    return access((target, budget) -> () -> {});
  }

  SiteAccess access(RequestCoordinator coordinator) {
    return new SiteAccess(
        coordinator,
        host -> {
          if (host.equals("site.example") || host.equals("other.example")) {
            return List.of(local);
          }
          return PublicUrls.resolvePublic(host, ignored -> List.of(local));
        });
  }

  static WorkBudget work(Duration duration) {
    return work(duration, () -> false);
  }

  static WorkBudget work(Duration duration, BooleanSupplier cancelled) {
    return new WorkBudget(Instant.now().plus(duration), cancelled, deadline -> {});
  }

  static void reply(HttpExchange exchange, int status, String type, String content)
      throws IOException {
    exchange.getResponseHeaders().set("Content-Type", type);
    byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
    exchange.getResponseBody().write(bytes);
  }

  static void redirect(HttpExchange exchange, String location) throws IOException {
    exchange.getResponseHeaders().set("Location", location);
    exchange.sendResponseHeaders(302, -1);
  }

  @Override
  public void close() {
    server.stop(0);
    executor.shutdownNow();
  }
}
