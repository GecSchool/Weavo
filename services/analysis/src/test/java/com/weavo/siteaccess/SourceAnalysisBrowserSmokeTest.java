package com.weavo.siteaccess;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.weavo.analysis.source.application.SourceAnalyzer;
import com.weavo.analysis.source.application.SourceCandidate;
import com.weavo.analysis.source.application.SourceCandidateGenerator;
import com.weavo.extraction.FieldValueConverter.Type;
import com.weavo.extraction.FieldValueProcessor;
import com.weavo.extraction.JavaFieldParser;
import com.weavo.siteaccess.rule.FieldConnector;
import com.weavo.siteaccess.rule.FieldConnector.DetailAccess;
import com.weavo.siteaccess.rule.FieldConnector.DetailAccessMethod;
import com.weavo.siteaccess.rule.FieldConnector.DetailNavigation;
import com.weavo.siteaccess.rule.FieldConnector.DetailOpen;
import com.weavo.siteaccess.rule.FieldConnector.DetailReturn;
import com.weavo.siteaccess.rule.FieldConnector.Extract;
import com.weavo.siteaccess.rule.FieldConnector.ExtractType;
import com.weavo.siteaccess.rule.FieldConnector.Location;
import com.weavo.siteaccess.rule.FieldConnector.OpenType;
import com.weavo.siteaccess.rule.FieldConnector.Parser;
import com.weavo.siteaccess.rule.FieldConnector.ReturnType;
import com.weavo.siteaccess.rule.FieldConnector.Target;
import com.weavo.siteaccess.rule.Navigation;
import com.weavo.siteaccess.rule.Navigation.AccessMethod;
import com.weavo.siteaccess.rule.Navigation.ListAccess;
import com.weavo.siteaccess.rule.Navigation.Pagination;
import com.weavo.siteaccess.rule.Navigation.PaginationType;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@EnabledIfSystemProperty(named = "weavo.browser.smoke", matches = "true")
class SourceAnalysisBrowserSmokeTest {
  @Test
  void validatesBrowserAndParserRowsDetailTypesAndActualNextPage() throws Exception {
    try (var site = new TestSite(false)) {
      var analyzer =
          analyzer(
              site,
              (input, budget) -> {
                assertThat(input.firstListDom()).contains("article");
                assertThat(input.collectionPurpose()).isEqualTo("공지 수집");
                return candidate(
                    input.listUrl(), "h2", "return rawValues.getFirst().strip();", "a.next");
              });
      var result = analyzer.analyze(site.url(), "공지 수집", budget(Duration.ofSeconds(12)));

      assertThat(result.failure()).isNull();
      assertThat(result.candidate().navigation().listAccess().method())
          .isEqualTo(AccessMethod.BROWSER);
      assertThat(result.samples()).hasSize(3);
      assertThat(result.samples().getFirst())
          .containsEntry("title", "Item 1")
          .containsEntry("body", "Body 1")
          .containsEntry("absent", null);
      assertThat(site.details.get()).isEqualTo(3);
      assertThat(site.next.get()).isEqualTo(1);
    }
  }

  @Test
  void passesTheFailedRuleBackForCorrectionBeforeAcceptingResults() throws Exception {
    try (var site = new TestSite(false)) {
      var calls = new AtomicInteger();
      var analyzer =
          analyzer(
              site,
              (input, budget) -> {
                if (calls.incrementAndGet() == 1) {
                  return candidate(input.listUrl(), ".missing", null, "a.next");
                }
                assertThat(input.previousFailure().candidateKey()).isEqualTo("title");
                assertThat(input.previousFailure().reason()).isEqualTo("NO_MATCH");
                assertThat(input.previousCandidate()).isNotNull();
                return candidate(input.listUrl(), "h2", null, "a.next");
              });
      var result = analyzer.analyze(site.url(), "공지 수집", budget(Duration.ofSeconds(12)));
      assertThat(result.failure()).isNull();
      assertThat(result.samples()).hasSize(3);
      assertThat(calls.get()).isEqualTo(2);
    }
  }

  @Test
  void retriesWithTheObservedDetailDomWhenAFieldSelectorDoesNotMatch() throws Exception {
    try (var site = new TestSite(false)) {
      var calls = new AtomicInteger();
      var analyzer =
          analyzer(
              site,
              (input, budget) -> {
                var valid = candidate(input.listUrl(), "h2", null, "a.next");
                if (calls.incrementAndGet() > 1) {
                  assertThat(input.observedScreens().get("DETAIL")).contains("Body 1");
                  assertThat(input.previousFailure().candidateKey()).isEqualTo("body");
                  return valid;
                }
                var proposed = new java.util.ArrayList<>(valid.fields());
                var body = proposed.get(1);
                var connector = body.connector();
                proposed.set(
                    1,
                    new SourceCandidate.Field(
                        body.name(),
                        body.type(),
                        body.nullable(),
                        body.reason(),
                        new FieldConnector(
                            connector.fieldId(),
                            connector.location(),
                            ".not-the-body",
                            connector.extract(),
                            connector.parser(),
                            connector.detailNavigation())));
                return new SourceCandidate(valid.navigation(), proposed);
              });
      var output = analyzer.analyze(site.url(), "공지 본문 수집", budget(Duration.ofSeconds(12)));
      assertThat(output.failure()).isNull();
      assertThat(output.samples().getFirst()).containsEntry("body", "Body 1");
      assertThat(calls.get()).isEqualTo(2);
    }
  }

  @Test
  void keepsExecutorTransportFailureOutOfCandidateCorrection() throws Exception {
    try (var site = new TestSite(false)) {
      var calls = new AtomicInteger();
      var fields =
          new FieldValueProcessor((code, values) -> {
            throw new FieldValueProcessor.ParserUnavailable("HTTP_503");
          });
      var analyzer =
          new SourceAnalyzer(
              site.access(),
              fields,
              (input, budget) -> {
                calls.incrementAndGet();
                return candidate(input.listUrl(), "h2", "return rawValues.getFirst();", "a.next");
              });
      var output = analyzer.analyze(site.url(), "공지 수집", budget(Duration.ofSeconds(12)));
      assertThat(output.failure().stage()).isEqualTo("PARSER_CALL");
      assertThat(output.samples()).isEmpty();
      assertThat(calls.get()).isEqualTo(1);
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void rejectsInvalidJavaAndNavigationWithoutReturningApprovedSamples(boolean invalidJava)
      throws Exception {
    try (var site = new TestSite(false)) {
      var calls = new AtomicInteger();
      var analyzer =
          analyzer(
              site,
              (input, budget) ->
                  calls.incrementAndGet() == 1
                      ? candidate(
                          input.listUrl(),
                          "h2",
                          invalidJava ? "invalid Java;" : null,
                          invalidJava ? "a.next" : ".missing-next")
                      : null);
      var result = analyzer.analyze(site.url(), "공지 수집", budget(Duration.ofSeconds(12)));

      assertThat(result.candidate()).isNull();
      assertThat(result.samples()).isEmpty();
      assertThat(result.failure().stage()).isEqualTo(invalidJava ? "PARSER" : "LIST_NAVIGATION");
    }
  }

  @Test
  void templateStartMustResolveToTheSameRowsAsTheRequestedList() throws Exception {
    try (var site = new TestSite(false)) {
      var calls = new AtomicInteger();
      var analyzer =
          analyzer(
              site,
              (input, budget) -> {
                if (calls.incrementAndGet() > 1) {
                  return null;
                }
                var ordinary = candidate(input.listUrl(), "h2", null, "a.next");
                String template = input.listUrl().replace("/list", "/set/{value}");
                var rule =
                    new Navigation(
                        input.listUrl(),
                        List.of(),
                        ordinary.navigation().listAccess(),
                        "article",
                        new Pagination(
                            PaginationType.URL_TEMPLATE, null, null, "main", template, 8, 1, null));
                return new SourceCandidate(rule, ordinary.fields());
              });
      var result = analyzer.analyze(site.url(), "공지 수집", budget(Duration.ofSeconds(12)));
      assertThat(result.failure().reason()).isEqualTo("TEMPLATE_START_MISMATCH");
      assertThat(result.samples()).isEmpty();
      assertThat(result.candidate()).isNull();
      assertThat(site.details.get()).isZero();
    }
  }

  @Test
  void accessDenialPreventsCandidateGeneration() throws Exception {
    try (var site = new TestSite(true)) {
      var analyzer =
          analyzer(
              site,
              (input, budget) -> {
                throw new AssertionError("Blocked DOM must not reach the candidate generator");
              });
      var result = analyzer.analyze(site.url(), "공지 수집", budget(Duration.ofSeconds(6)));
      assertThat(result.candidate()).isNull();
      assertThat(result.failure().stage()).isEqualTo("ACCESS");
      assertThat(result.failure().reason()).isEqualTo("ROBOTS_DENIED");
    }
  }

  @Test
  void deadlineDoesNotPromoteAnUnexecutedCandidate() throws Exception {
    try (var site = new TestSite(false)) {
      var analyzer =
          analyzer(
              site,
              (input, budget) -> {
                TimeUnit.MILLISECONDS.sleep(budget.remaining().toMillis() + 25);
                return candidate(input.listUrl(), "h2", null, "a.next");
              });
      var result = analyzer.analyze(site.url(), "공지 수집", budget(Duration.ofSeconds(3)));
      assertThat(result.candidate()).isNull();
      assertThat(result.samples()).isEmpty();
      assertThat(result.failure().stage()).isEqualTo("TIME_LIMIT");
      assertThat(site.details.get()).isZero();
    }
  }

  private static SourceCandidate candidate(String url, String title, String parser, String next) {
    var rule =
        new Navigation(
            url,
            List.of(),
            new ListAccess(AccessMethod.BROWSER, "main"),
            "article",
            new Pagination(PaginationType.NEXT_LINK, next, "href", "main", null, null, null, null));
    var detail =
        new DetailNavigation(
            new DetailAccess(DetailAccessMethod.BROWSER, ".body"),
            new DetailOpen(OpenType.LINK, "a", "href", Target.CURRENT_TAB),
            new DetailReturn(ReturnType.HISTORY_BACK, "main", null));
    var text = new Extract(ExtractType.TEXT, null);
    return new SourceCandidate(
        rule,
        List.of(
            new SourceCandidate.Field(
                "Title",
                Type.STRING,
                false,
                "Item heading",
                new FieldConnector(
                    "title",
                    Location.LIST,
                    title,
                    text,
                    parser == null ? null : new Parser(parser),
                    null)),
            new SourceCandidate.Field(
                "Body",
                Type.STRING,
                false,
                "Detail text",
                new FieldConnector("body", Location.DETAIL, ".body", text, null, detail)),
            new SourceCandidate.Field(
                "Summary",
                Type.STRING,
                true,
                "Unavailable on this source",
                new FieldConnector("absent", Location.ABSENT, null, null, null, null))));
  }

  private static SourceAnalyzer analyzer(TestSite site, SourceCandidateGenerator generator) {
    return new SourceAnalyzer(
        site.access(), new FieldValueProcessor(new JavaFieldParser()), generator);
  }

  private static WorkBudget budget(Duration duration) {
    return new WorkBudget(Instant.now().plus(duration), () -> false, deadline -> {});
  }

  private static final class TestSite implements AutoCloseable {
    private final HttpServer server;
    private final InetAddress address;
    private final AtomicInteger next = new AtomicInteger();
    private final AtomicInteger details = new AtomicInteger();

    TestSite(boolean disallowed) throws IOException {
      address = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
      server = HttpServer.create(new InetSocketAddress(address, 0), 0);
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              String path = exchange.getRequestURI().getPath();
              String body;
              if (path.equals("/robots.txt")) {
                if (!disallowed) {
                  exchange.sendResponseHeaders(404, -1);
                  return;
                }
                exchange.getResponseHeaders().set("Content-Type", "text/plain;charset=utf-8");
                body = "User-agent: *\nDisallow: /list\n";
              } else {
                exchange.getResponseHeaders().set("Content-Type", "text/html;charset=utf-8");
                if (path.startsWith("/set/")) {
                  body = "<main>" + item(9) + "</main>";
                } else if (path.equals("/next")) {
                  next.incrementAndGet();
                  body = "<main>" + item(5) + "</main>";
                } else if (path.startsWith("/detail/")) {
                  details.incrementAndGet();
                  body =
                      "<div class=body>Body "
                          + path.substring(path.lastIndexOf('/') + 1)
                          + "</div>";
                } else {
                  body =
                      "<main>"
                          + item(1)
                          + item(2)
                          + item(3)
                          + item(4)
                          + "<a class=next href=/next>Next</a></main>";
                }
              }
              byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes);
            }
          });
      server.start();
    }

    String url() {
      return "http://fixture.example:" + server.getAddress().getPort() + "/list";
    }

    SiteAccess access() {
      return new SiteAccess((url, budget) -> () -> {}, host -> List.of(address));
    }

    private static String item(int value) {
      return "<article><h2>Item "
          + value
          + "</h2><a href=/detail/"
          + value
          + ">Details</a></article>";
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
