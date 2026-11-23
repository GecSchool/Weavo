package com.weavo.siteaccess;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.weavo.analysis.source.application.SourceAnalyzer;
import com.weavo.analysis.source.application.SourceCandidate;
import com.weavo.analysis.source.application.SourceCandidateGenerator;
import com.weavo.analysis.source.infrastructure.http.OpenAiCandidateGenerator;
import com.weavo.extraction.FieldValueConverter.Type;
import com.weavo.extraction.FieldValueProcessor;
import com.weavo.extraction.JavaFieldParser;
import com.weavo.extraction.infrastructure.IsolatedParserClient;
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
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@EnabledIfSystemProperty(named = "weavo.browser.smoke", matches = "true")
class SourceAnalysisBrowserSmokeTest {
  @Test
  @EnabledIfEnvironmentVariable(named = "WEAVO_RUN_OPENAI_SMOKE", matches = "1")
  void terraProposalCollectsTheDetailBodyWithTheActualBrowser() throws Exception {
    try (var site = new TestSite(false)) {
      var generator =
          new OpenAiCandidateGenerator(
              System.getenv("OPENAI_API_KEY"), OpenAiCandidateGenerator.DEFAULT_MODEL);
      var parser =
          new IsolatedParserClient(
              URI.create(System.getenv("WEAVO_PARSER_URL")), System.getenv("WEAVO_PARSER_TOKEN"));
      var budget = budget(Duration.ofMinutes(5));
      var fields =
          new FieldValueProcessor((code, values) ->
              parser.parse(code, values, budget::beforeRequest, budget::remainingWhileWaiting));
      var analyzer = new SourceAnalyzer(site.access(), fields, generator);
      var result =
          analyzer.analyze(
              site.url(),
              "Collect each notice title and the full body text found only on its detail page.",
              budget);
      assertThat(result.failure()).isNull();
      var detail =
          result.candidate().fields().stream()
              .filter(field -> field.connector().location() == Location.DETAIL)
              .map(field -> field.connector().fieldId())
              .toList();
      assertThat(detail).isNotEmpty();
      assertThat(result.samples()).isNotEmpty();
      assertThat(result.samples().getFirst().values()).contains("Body 1");
      assertThat(site.details.get()).isPositive();
    }
  }

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
  void confirmsDetailFieldsFromTheOpenedPageBeforeAcceptingValues() throws Exception {
    try (var site = new TestSite(false)) {
      var proposals = new AtomicInteger();
      var refinements = new AtomicInteger();
      var generator =
          generator(
              (input, budget) -> {
                proposals.incrementAndGet();
                var proposed = candidate(input.listUrl(), "h2", null, "a.next");
                var fields = new java.util.ArrayList<>(proposed.fields());
                var body = fields.get(1);
                var connector = body.connector();
                fields.set(
                    1,
                    new SourceCandidate.Field(
                        body.name(),
                        body.type(),
                        body.nullable(),
                        body.reason(),
                        new FieldConnector(
                            connector.fieldId(),
                            Location.DETAIL,
                            "body",
                            connector.extract(),
                            null,
                            connector.detailNavigation())));
                return new SourceCandidate(proposed.navigation(), fields);
              },
              input -> {
                refinements.incrementAndGet();
                assertThat(input.collectionPurpose()).isEqualTo("공지 본문 수집");
                assertThat(input.samples())
                    .singleElement()
                    .satisfies(sample -> {
                      assertThat(sample.dom()).contains("Body 1", "Share this notice");
                      assertThat(sample.fields())
                          .singleElement()
                          .satisfies(field -> assertThat(field.fieldId()).isEqualTo("body"));
                    });
                return detailRules(input, ".body");
              });
      var analyzer =
          new SourceAnalyzer(
              site.access(), new FieldValueProcessor(new JavaFieldParser()), generator);
      var output = analyzer.analyze(site.url(), "공지 본문 수집", budget(Duration.ofSeconds(15)));
      assertThat(output.failure()).isNull();
      assertThat(output.candidate().fields().get(1).connector().selector()).isEqualTo(".body");
      assertThat(output.samples().stream().map(sample -> sample.get("body")))
          .containsExactly("Body 1", "Body 2", "Body 3");
      assertThat(proposals.get()).isEqualTo(1);
      assertThat(refinements.get()).isEqualTo(1);
      assertThat(site.details.get()).isEqualTo(3);
      assertThat(site.next.get()).isEqualTo(1);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"body", "html > body", ".missing"})
  void retriesWithObservedDetailsWhenTheRefinementCannotBeUsed(String selector) throws Exception {
    try (var site = new TestSite(false)) {
      var calls = new AtomicInteger();
      var generator =
          generator(
              (input, budget) -> {
                if (calls.incrementAndGet() > 1) {
                  assertThat(input.observedScreens().get("DETAIL")).contains("Body 1");
                  assertThat(input.previousFailure().candidateKey()).isEqualTo("body");
                  assertThat(input.previousFailure().reason())
                      .isEqualTo(
                          selector.equals(".missing")
                              ? "NO_MATCH"
                              : "DETAIL_SELECTOR_MATCHES_PAGE_ROOT");
                  return null;
                }
                return candidate(input.listUrl(), "h2", null, "a.next");
              },
              input -> detailRules(input, selector));
      var analyzer =
          new SourceAnalyzer(
              site.access(), new FieldValueProcessor(new JavaFieldParser()), generator);
      var output = analyzer.analyze(site.url(), "공지 본문 수집", budget(Duration.ofSeconds(12)));
      assertThat(output.candidate()).isNull();
      assertThat(output.samples()).isEmpty();
      assertThat(output.failure().stage()).isEqualTo("EXTRACTION");
      assertThat(calls.get()).isEqualTo(2);
    }
  }

  @Test
  void listOnlyDiscoveryFinishesWithOneProposal() throws Exception {
    try (var site = new TestSite(false)) {
      var proposals = new AtomicInteger();
      var generator =
          generator(
              (input, budget) -> {
                proposals.incrementAndGet();
                var proposed = candidate(input.listUrl(), "h2", null, "a.next");
                return new SourceCandidate(
                    proposed.navigation(), List.of(proposed.fields().getFirst()));
              },
              input -> {
                throw new AssertionError("List-only discovery has no detail refinement");
              });
      var analyzer =
          new SourceAnalyzer(
              site.access(), new FieldValueProcessor(new JavaFieldParser()), generator);
      var result = analyzer.analyze(site.url(), "공지 제목 수집", budget(Duration.ofSeconds(12)));
      assertThat(result.failure()).isNull();
      assertThat(result.samples()).hasSize(4);
      assertThat(proposals.get()).isEqualTo(1);
      assertThat(site.details.get()).isZero();
      assertThat(site.next.get()).isEqualTo(1);
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
              generator(
                  (input, budget) -> {
                    calls.incrementAndGet();
                    return candidate(
                        input.listUrl(), "h2", "return rawValues.getFirst();", "a.next");
                  },
                  refinement -> {
                    throw new AssertionError("List parser failure stops discovery");
                  }));
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void finalVerificationUsesHttpOnlyForEquivalentLists(boolean browserOnly) throws Exception {
    try (var site = new TestSite(false, browserOnly)) {
      var analyzer =
          analyzer(
              site,
              (input, budget) -> {
                throw new AssertionError("Final verification must reuse the approved candidate");
              });
      var verified =
          analyzer.verify(
              candidate(site.url(), "h2", null, "a.next"), budget(Duration.ofSeconds(15)));
      assertThat(verified.failure()).isNull();
      assertThat(verified.navigation().listAccess().method())
          .isEqualTo(browserOnly ? AccessMethod.BROWSER : AccessMethod.HTTP);
      assertThat(verified.samples()).hasSize(3);
      assertThat(verified.samples().getFirst().sourceUrl()).endsWith("/detail/1");
      assertThat(verified.samples().getFirst().fields())
          .anySatisfy(field -> {
            assertThat(field.fieldId()).isEqualTo("absent");
            assertThat(field.result().status())
                .isEqualTo(com.weavo.extraction.FieldExtractor.Status.ABSENT);
          });
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {true, false})
  void verifiesTheLastPageWithAnExplicitlyDisabledNextLink(boolean disabledInHttp)
      throws Exception {
    String next =
        disabledInHttp
            ? "<a class=next href=/next aria-disabled=true>Next</a>"
            : "<a class=next href=/next>Next</a><script>"
                + "document.querySelector('a.next').setAttribute('aria-disabled', 'true');</script>";
    try (var site = new TestSite(false, false, next)) {
      var analyzer =
          analyzer(
              site,
              (input, budget) -> {
                throw new AssertionError("Revalidation must use the existing rule");
              });
      var verified =
          analyzer.verify(
              candidate(site.url(), "h2", null, "a.next"), budget(Duration.ofSeconds(15)));

      assertThat(verified.failure()).isNull();
      assertThat(verified.samples()).hasSize(3);
      assertThat(verified.navigation().pagination().type()).isEqualTo(PaginationType.NEXT_LINK);
      assertThat(verified.navigation().listAccess().method())
          .isEqualTo(disabledInHttp ? AccessMethod.HTTP : AccessMethod.BROWSER);
      assertThat(site.next.get()).isZero();
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

  private static SourceAnalyzer analyzer(TestSite site, Proposal propose) {
    return new SourceAnalyzer(
        site.access(),
        new FieldValueProcessor(new JavaFieldParser()),
        generator(propose, input -> detailRules(input, ".body")));
  }

  private static SourceCandidateGenerator generator(
      Proposal propose,
      Function<SourceCandidateGenerator.DetailInput, List<SourceCandidateGenerator.DetailField>>
          refine) {
    return new SourceCandidateGenerator() {
      @Override
      public SourceCandidate propose(Input input, WorkBudget budget) throws InterruptedException {
        return propose.generate(input, budget);
      }

      @Override
      public List<DetailField> refineDetails(DetailInput input, WorkBudget budget) {
        return refine.apply(input);
      }
    };
  }

  private static List<SourceCandidateGenerator.DetailField> detailRules(
      SourceCandidateGenerator.DetailInput input, String selector) {
    return input.samples().stream()
        .flatMap(sample -> sample.fields().stream())
        .map(field ->
            new SourceCandidateGenerator.DetailField(
                field.fieldId(), selector, new Extract(ExtractType.TEXT, null), null))
        .toList();
  }

  private static WorkBudget budget(Duration duration) {
    return new WorkBudget(Instant.now().plus(duration), () -> false, deadline -> {});
  }

  @FunctionalInterface
  private interface Proposal {
    SourceCandidate generate(SourceCandidateGenerator.Input input, WorkBudget budget)
        throws InterruptedException;
  }

  static final class TestSite implements AutoCloseable {
    private final HttpServer server;
    private final InetAddress address;
    private final AtomicInteger next = new AtomicInteger();
    private final AtomicInteger details = new AtomicInteger();

    TestSite(boolean disallowed) throws IOException {
      this(disallowed, false);
    }

    TestSite(boolean disallowed, boolean browserOnly) throws IOException {
      this(disallowed, browserOnly, "<a class=next href=/next>Next</a>");
    }

    TestSite(boolean disallowed, boolean browserOnly, String nextControl) throws IOException {
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
                if (path.equals("/bulletins") || path.equals("/bulletins-next")) {
                  if (path.endsWith("-next")) {
                    next.incrementAndGet();
                    body = pipelineList(pipelineRow("Exam timetable", "/detail/5", null), null);
                  } else {
                    body =
                        pipelineList(
                            pipelineRow("Scholarship applications", "/detail/1", null)
                                + pipelineRow("Library hours", "/detail/2", null),
                            "/bulletins-next");
                  }
                } else if (path.equals("/briefs")) {
                  body =
                      pipelineList(
                          pipelineRow("Club fair", null, "Meet in the main hall.")
                              + pipelineRow("Campus shuttle", null, "The shuttle runs until six."),
                          null);
                } else if (path.startsWith("/set/")) {
                  body = "<main>" + item(9) + "</main>";
                } else if (path.equals("/next")) {
                  next.incrementAndGet();
                  body = "<main>" + item(5) + "</main>";
                } else if (path.startsWith("/detail/")) {
                  details.incrementAndGet();
                  body =
                      "<header>University portal</header><main><article><h1>Notice title</h1>"
                          + "<div class=body>Body "
                          + path.substring(path.lastIndexOf('/') + 1)
                          + "</div><footer>Share this notice</footer></article></main>"
                          + "<footer>Campus links</footer>";
                } else {
                  body =
                      "<main>"
                          + item(1)
                          + item(2)
                          + item(3)
                          + item(4)
                          + nextControl
                          + "</main>"
                          + (browserOnly
                              ? "<script>document.querySelector('h2').textContent='Browser item';</script>"
                              : "");
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
      return url("/list");
    }

    String url(String path) {
      return "http://fixture.example:" + server.getAddress().getPort() + path;
    }

    private static String pipelineList(String rows, String nextPage) {
      String pagination =
          nextPage == null
              ? ""
              : "<nav aria-label=Pagination><a class=next rel=next href="
                  + nextPage
                  + ">Next page</a></nav>";
      return "<header>Campus navigation</header><main><h1>University notices</h1>"
          + "<aside><article class=sponsored-card><h2>Sponsored story</h2></article></aside>"
          + "<section aria-label=Notices>"
          + rows
          + "</section>"
          + pagination
          + "</main><footer>Campus directory</footer>";
    }

    private static String pipelineRow(String title, String detail, String body) {
      String content =
          detail == null
              ? "<p class=notice-body>" + body + "</p>"
              : "<a class=notice-details href=" + detail + ">Notice detail</a>";
      return "<article class=notice-card><header><h2 class=notice-title>"
          + title
          + "</h2></header>"
          + content
          + "</article>";
    }

    int nextRequests() {
      return next.get();
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
