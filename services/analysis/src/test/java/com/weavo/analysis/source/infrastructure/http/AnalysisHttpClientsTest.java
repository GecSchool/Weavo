package com.weavo.analysis.source.infrastructure.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.weavo.analysis.source.application.SourceCandidateGenerator;
import com.weavo.extraction.FieldValueProcessor.ParserUnavailable;
import com.weavo.extraction.JavaFieldParser.Stage;
import com.weavo.extraction.infrastructure.IsolatedParserClient;
import com.weavo.siteaccess.WorkBudget;
import com.weavo.siteaccess.rule.FieldConnector.Location;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class AnalysisHttpClientsTest {
  private static final String LIST = "https://example.org/notices";
  private static final String DOM =
      """
      <html><head><script>ignored()</script></head><body>
      <ul class="notices">
        <li class="notice"><h2>Scholarship opened</h2><a href="/notices/1">Details</a></li>
        <li class="notice"><h2>Library schedule</h2><a href="/notices/2">Details</a></li>
      </ul><a class="next" href="/notices?page=2">Next</a>
      </body></html>
      """;
  private static final String PROPOSAL =
      """
      {
        "navigation": {"initial_actions":[],"wait_selector":null,"item_selector":".notice",
          "pagination":{"type":"NEXT_LINK","selector":".next","url_attribute":"href","wait_selector":null,
          "url_template":null,"start":null,"step":null,"scroll_container_selector":null}},
        "fields": [{"name":"title","type":"STRING","nullable":true,"reason":"Visible heading",
          "location":"LIST","selector":"h2","extract":{"type":"TEXT","attribute":null},
          "parser_code":null,"detail_navigation":null}]
      }
      """;

  @Test
  void sendsStructuredOutputRequestAndBuildsAValidatedDomainObject() throws Exception {
    var observed = new AtomicReference<JsonNode>();
    var auth = new AtomicReference<String>();
    try (var server =
        new Stub(exchange -> {
          auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
          observed.set(read(exchange));
          var content = Map.of("type", "output_text", "text", PROPOSAL);
          reply(
              exchange,
              200,
              Map.of(
                  "status",
                  "completed",
                  "output",
                  List.of(Map.of("type", "message", "content", List.of(content)))));
        })) {
      var provider =
          new OpenAiCandidateGenerator(
              HttpClient.newHttpClient(),
              server.url().resolve("/v1/responses"),
              "fixture-key",
              OpenAiCandidateGenerator.DEFAULT_MODEL);
      var result = provider.propose(input(), budget());
      var request = observed.get();
      assertThat(auth.get()).isEqualTo("Bearer fixture-key");
      assertThat(request.path("model").asText()).isEqualTo("gpt-6.1-terra");
      assertThat(request.at("/text/format/strict").asBoolean()).isTrue();
      assertThat(request.path("input").asText())
          .contains("Scholarship opened")
          .doesNotContain("ignored()");
      assertThat(result.navigation().listUrl()).isEqualTo(LIST);
      assertThat(result.fields().getFirst().connector().location()).isEqualTo(Location.LIST);
    }
  }

  @Test
  void classifiesAnIncompleteModelResponseAsUnavailable() throws Exception {
    try (var server = new Stub(exchange -> reply(exchange, 200, Map.of("status", "incomplete")))) {
      var provider =
          new OpenAiCandidateGenerator(
              HttpClient.newHttpClient(), server.url(), "fixture-key", "gpt-6.1-terra");
      assertThatThrownBy(() -> provider.propose(input(), budget()))
          .isInstanceOf(SourceCandidateGenerator.Unavailable.class)
          .hasMessage("RESPONSE_INCOMPLETE");
    }
  }

  @Test
  void retriesTemporaryParserFailureAndPreservesTheIndividualCodeFailure() throws Exception {
    var calls = new AtomicInteger();
    var firstKey = new AtomicReference<String>();
    var nextKey = new AtomicReference<String>();
    var observed = new AtomicReference<JsonNode>();
    try (var server =
        new Stub(exchange -> {
          var request = read(exchange);
          observed.set(request);
          String key = request.at("/items/0/item_key").asText();
          if (calls.incrementAndGet() == 1) {
            firstKey.set(key);
            reply(exchange, 503, Map.of());
            return;
          }
          nextKey.set(key);
          reply(
              exchange,
              200,
              Map.of(
                  "results",
                  List.of(
                      Map.of("item_key", key, "status", "FAILED", "failure", "EXECUTION_LIMIT"))));
        })) {
      var budget = budget();
      var result =
          new IsolatedParserClient(server.url(), "fixture-token")
              .parse(
                  "return rawValues.get(0);",
                  List.of("name"),
                  budget::beforeRequest,
                  budget::remainingWhileWaiting);
      assertThat(calls.get()).isEqualTo(2);
      assertThat(firstKey.get()).isEqualTo(nextKey.get());
      assertThat(observed.get().at("/items/0/raw_values/0").asText()).isEqualTo("name");
      assertThat(observed.get().path("timeout_ms").asLong()).isBetween(1L, 10000L);
      assertThat(result.failure().stage()).isEqualTo(Stage.EXECUTION_LIMIT);
    }
  }

  @Test
  void rejectsAParserResponseForAnUnrelatedItem() throws Exception {
    try (var server =
        new Stub(exchange ->
            reply(
                exchange,
                200,
                Map.of(
                    "results",
                    List.of(
                        Map.of(
                            "item_key",
                            "someone-else",
                            "status",
                            "SUCCEEDED",
                            "value",
                            "unrelated")))))) {
      var parser = new IsolatedParserClient(server.url(), "fixture-token");
      var budget = budget();
      assertThatThrownBy(() ->
              parser.parse(
                  "return null;",
                  List.of("value"),
                  budget::beforeRequest,
                  budget::remainingWhileWaiting))
          .isInstanceOf(ParserUnavailable.class)
          .hasMessage("INVALID_PARSER_RESPONSE");
    }
  }

  @Test
  void keepsContentHeadersAndPaginationWhenReducingOversizedListEvidence() {
    String html =
        "<header>Campus navigation</header><main><section>"
            + ("<article class=notice><header>Notice title</header><a href=/detail>Details</a>"
                    + "a".repeat(700)
                    + "</article>")
                .repeat(105)
            + "</section><footer><a rel=next href=/page/2>Next</a></footer></main>"
            + "<footer>Campus directory</footer>";
    String compact = AnalysisDom.compact(html, true);
    assertThat(compact).doesNotContain("Campus navigation", "Campus directory");
    var document = org.jsoup.Jsoup.parse(compact);
    assertThat(document.select("main article.notice")).hasSize(3);
    assertThat(document.select("article header").eachText()).containsOnly("Notice title");
    assertThat(document.select("article a[href=/detail]")).hasSize(3);
    assertThat(document.select("main footer a[rel=next]").attr("href")).isEqualTo("/page/2");
  }

  @Test
  void sendsObservedDetailDomAndGetsOnlyTheRequestedFieldRules() throws Exception {
    var observed = new AtomicReference<JsonNode>();
    try (var server =
        new Stub(exchange -> {
          observed.set(read(exchange));
          reply(
              exchange,
              200,
              Map.of(
                  "status",
                  "completed",
                  "output",
                  List.of(
                      Map.of(
                          "type",
                          "message",
                          "content",
                          List.of(
                              Map.of(
                                  "type",
                                  "output_text",
                                  "text",
                                  """
                                  {"fields":[{"field_id":"notice-body","selector":".content",
                                    "extract":{"type":"TEXT","attribute":null},"parser_code":null}]}
                                  """))))));
        })) {
      var provider =
          new OpenAiCandidateGenerator(
              HttpClient.newHttpClient(),
              server.url(),
              "fixture-key",
              OpenAiCandidateGenerator.DEFAULT_MODEL);
      var input =
          new SourceCandidateGenerator.DetailInput(
              "Collect notice bodies",
              List.of(
                  new SourceCandidateGenerator.DetailSample(
                      "<header>Site menu</header><article><header>Notice heading</header>"
                          + "<div class=content>Observed body</div></article><footer>Directory</footer>",
                      List.of(
                          new SourceCandidateGenerator.DetailTarget(
                              "notice-body", "Body", "STRING", false, "Full notice text")))));
      assertThat(provider.refineDetails(input, budget()))
          .singleElement()
          .satisfies(field -> {
            assertThat(field.fieldId()).isEqualTo("notice-body");
            assertThat(field.selector()).isEqualTo(".content");
          });
      var request = observed.get();
      assertThat(request.at("/text/format/name").asText()).isEqualTo("detail_fields");
      var evidence = JsonMapper.shared().readTree(request.path("input").asText());
      assertThat(evidence.path("collection_purpose").asText()).isEqualTo("Collect notice bodies");
      assertThat(evidence.at("/samples/0/fields/0/field_id").asText()).isEqualTo("notice-body");
      assertThat(evidence.at("/samples/0/dom").asText())
          .contains("Notice heading", "Observed body")
          .doesNotContain("Site menu", "Directory");
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "WEAVO_RUN_OPENAI_SMOKE", matches = "1")
  void realTerraAcceptsTheCandidateSchemaAndReturnsUsableListRules() throws Exception {
    var provider =
        new OpenAiCandidateGenerator(
            System.getenv("OPENAI_API_KEY"), OpenAiCandidateGenerator.DEFAULT_MODEL);
    var result =
        provider.propose(
            input(), new WorkBudget(Instant.now().plusSeconds(75), () -> false, deadline -> {}));
    assertThat(result.navigation().listUrl()).isEqualTo(LIST);
    assertThat(com.weavo.extraction.HtmlScope.parse(DOM).select(result.navigation().itemSelector()))
        .hasSize(2);
    assertThat(result.fields())
        .anySatisfy(field -> assertThat(field.connector().location()).isEqualTo(Location.LIST));
  }

  private static SourceCandidateGenerator.Input input() {
    return new SourceCandidateGenerator.Input(LIST, "Collect notice titles", DOM, null, null);
  }

  private static WorkBudget budget() {
    return new WorkBudget(Instant.now().plusSeconds(10), () -> false, deadline -> {});
  }

  private static JsonNode read(HttpExchange exchange) throws IOException {
    return JsonMapper.shared().readTree(exchange.getRequestBody());
  }

  private static void reply(HttpExchange exchange, int status, Map<String, ?> payload)
      throws IOException {
    byte[] bytes = JsonMapper.shared().writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
    exchange.sendResponseHeaders(status, bytes.length);
    exchange.getResponseBody().write(bytes);
    exchange.close();
  }

  private static final class Stub implements AutoCloseable {
    private final HttpServer server;

    Stub(com.sun.net.httpserver.HttpHandler handler) throws IOException {
      server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
      server.createContext("/", handler);
      server.start();
    }

    URI url() {
      return URI.create("http://localhost:" + server.getAddress().getPort());
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
