package com.weavo.siteaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import com.weavo.crawler.collection.application.FixedCollection;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionExecute;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionProject;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.LatestRecord;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.Schema;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.SchemaField;
import com.weavo.extraction.FieldExtractor;
import com.weavo.extraction.FieldValueProcessor;
import com.weavo.extraction.HtmlScope;
import com.weavo.extraction.JavaFieldParser;
import com.weavo.extraction.infrastructure.IsolatedParserClient;
import com.weavo.siteaccess.rule.FieldConnector;
import com.weavo.siteaccess.rule.Navigation;
import com.weavo.siteaccess.rule.Navigation.Pagination;
import com.weavo.siteaccess.rule.Navigation.PaginationType;
import com.weavo.worker.application.AttemptContext;
import com.weavo.worker.application.JobProcessor.Job;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class HttpListNavigationTest {
  private static final String NEXT = "<a class=next href='?page=2'>다음</a>";
  private static final String FIELD_ID = "95f7d7de-ec00-4fbe-b430-fa3a79763c18";

  @ParameterizedTest
  @ValueSource(strings = {"INITIAL", "REVISION"})
  @EnabledIfEnvironmentVariable(named = "WEAVO_PARSER_TEST_URL", matches = ".+")
  void validatesCrawledValuesThroughTheIsolatedExecutor(String reason) throws Exception {
    try (var site = new TestSite(Map.of("/list", page("금액42", "")))) {
      var parser =
          new IsolatedParserClient(
              URI.create(System.getenv("WEAVO_PARSER_TEST_URL")),
              System.getenv("WEAVO_PARSER_TEST_TOKEN"));
      var stored = new HashMap<String, byte[]>();
      var result =
          remoteCollector(site, stored, parser)
              .execute(jobWithParser(site.url("/list"), reason), attempt());

      assertThat(result.status()).isEqualTo("SUCCEEDED");
      assertThat(result.projectResults().getFirst().normalExtractedRecordCount()).isEqualTo(1);
      assertThat(stored).containsKey(result.snapshotRef().key());
    }
  }

  @ParameterizedTest
  @ValueSource(ints = {401, 503})
  void classifiesExecutorFailureWithoutTreatingTheRecordAsEmpty(int httpStatus) throws Exception {
    var calls = new AtomicInteger();
    var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/internal/v1/parser:execute",
        exchange -> {
          try (exchange) {
            calls.incrementAndGet();
            exchange.sendResponseHeaders(httpStatus, -1);
          }
        });
    server.start();
    try (var site = new TestSite(Map.of("/list", page("금액42", "")))) {
      var parser =
          new IsolatedParserClient(
              URI.create("http://localhost:" + server.getAddress().getPort()), "fixture-token");
      var stored = new HashMap<String, byte[]>();
      var result =
          remoteCollector(site, stored, parser)
              .execute(jobWithParser(site.url("/list"), "INITIAL"), attempt());

      assertThat(result.error().code()).isEqualTo("TECHNICAL_FAILURE");
      assertThat(result.error().stage()).isEqualTo("PARSER_CALL");
      assertThat(result.projectResults().getFirst().normalExtractedRecordCount()).isNull();
      assertThat(result.snapshotRef()).isNull();
      assertThat(stored).isEmpty();
      assertThat(calls.get()).isEqualTo(httpStatus == 503 ? 2 : 1);
    } finally {
      server.stop(0);
    }
  }

  @Test
  void classifiesStalledParserAsTechnicalFailureWhenTheJobDeadlineIsReached() throws Exception {
    var entered = new AtomicInteger();
    var finished = new java.util.concurrent.CountDownLatch(1);
    var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    server.createContext(
        "/internal/v1/parser:execute",
        exchange -> {
          try (exchange) {
            entered.incrementAndGet();
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write('{');
            exchange.getResponseBody().flush();
            try {
              finished.await(10, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
              Thread.currentThread().interrupt();
            }
          }
        });
    server.start();
    try (var site = new TestSite(Map.of("/list", page("금액42", "")))) {
      var parser =
          new IsolatedParserClient(
              URI.create("http://localhost:" + server.getAddress().getPort()), "fixture-token");
      var ctx = attempt();
      when(ctx.workDeadline()).thenReturn(Instant.now().plusSeconds(3));
      var result =
          remoteCollector(site, new HashMap<>(), parser)
              .execute(jobWithParser(site.url("/list"), "INITIAL"), ctx);
      assertThat(entered.get()).isPositive();
      assertThat(result.error().stage())
          .withFailMessage("Lost parser failure. Result was %s", result)
          .isEqualTo("PARSER_CALL");
    } finally {
      finished.countDown();
      server.stop(0);
    }
  }

  private static FixedCollection remoteCollector(
      TestSite site, Map<String, byte[]> stored, IsolatedParserClient parser) {
    return new FixedCollection(
        site.access((uri, budget) -> () -> {}),
        budget ->
            new FieldValueProcessor((code, values) ->
                parser.parse(code, values, budget::beforeRequest, budget::remainingWhileWaiting)),
        "artifacts",
        (key, type, bytes) -> stored.put(key, bytes));
  }

  private static Job<CollectionExecute> jobWithParser(String url, String reason) {
    var base = job(url, nextLink());
    var payload = base.payload();
    var target = payload.projects().getFirst();
    var schema =
        new Schema(
            List.of(new SchemaField(FIELD_ID, "price", null, null, null, "number", false, null)));
    var connector =
        new FieldConnector(
            FIELD_ID,
            FieldConnector.Location.LIST,
            "h2",
            new FieldConnector.Extract(FieldConnector.ExtractType.TEXT, null),
            new FieldConnector.Parser("return rawValues.getFirst().substring(2);"),
            null);
    var project =
        new CollectionProject(
            target.projectId(),
            target.projectSourceId(),
            target.schemaVersion(),
            target.connectorRevision(),
            target.collectionMode(),
            schema,
            List.of(connector),
            target.latestRecords());
    return new Job<>(
        base.id(),
        base.expiresAt(),
        base.workBudget(),
        new CollectionExecute(
            payload.sourceId(),
            reason,
            payload.maxDurationSeconds(),
            payload.navigationRevision(),
            payload.navigation(),
            List.of(project)));
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "<a class=next aria-disabled=true href='?page=3'>다음</a>"})
  void followsRelativeLinksAndStoresEachListsReplayableIndices(String endControl) throws Exception {
    try (var site =
        new TestSite(
            Map.of("/list", page("첫 글", NEXT), "/list?page=2", page("둘째 글", endControl)))) {
      var stored = new HashMap<String, byte[]>();
      var result = collector(site, stored).execute(job(site.url("/list"), nextLink()), attempt());

      assertThat(result.status()).isEqualTo("SUCCEEDED");
      assertThat(result.stopReason()).isEqualTo("END_OF_LIST");
      assertThat(result.listItemCount()).isEqualTo(2);
      assertThat(site.requests).containsExactly("/list", "/list?page=2");
      var manifest = JsonMapper.shared().readTree(stored.get(result.snapshotRef().key()));
      var lists = manifest.path("list_snapshots");
      assertThat(lists).hasSize(2);
      for (int index = 0; index < lists.size(); index++) {
        var list = lists.get(index);
        var item = list.path("items").get(0);
        assertThat(item.path("sequence_index").asInt()).isEqualTo(index);
        assertThat(item.path("item_index").asInt()).isZero();
        assertThat(item.path("record_key").asString())
            .startsWith(list.path("snapshot_id").asString());
        var scope =
            HtmlScope.parse(
                    new String(
                        stored.get(list.path("object_ref").path("key").asString()),
                        StandardCharsets.UTF_8))
                .selectItem(list.path("item_selector").asString(), item.path("item_index").asInt());
        assertThat(FieldExtractor.extractText(scope, "h2").rawValues())
            .containsExactly(index == 0 ? "첫 글" : "둘째 글");
      }
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/list?page=", "/pages/"})
  void expandsTemplateFromTheApprovedStartAndEndsAtAnEmptyPage(String prefix) throws Exception {
    try (var site =
        new TestSite(
            Map.of(
                prefix + "4", page("네 번째", ""),
                prefix + "6", page("여섯 번째", ""),
                prefix + "8", "<main></main>"))) {
      var stored = new HashMap<String, byte[]>();
      var rule =
          new Pagination(
              PaginationType.URL_TEMPLATE,
              null,
              null,
              null,
              site.url(prefix + "{value}"),
              4,
              2,
              null);
      var result = collector(site, stored).execute(job(site.url(prefix + "4"), rule), attempt());

      assertThat(site.requests).containsExactly(prefix + "4", prefix + "6", prefix + "8");
      assertThat(result.stopReason()).isEqualTo("END_OF_LIST");
      assertThat(result.listItemCount()).isEqualTo(2);
      assertThat(stored).hasSize(3);
    }
  }

  @Test
  void noneDoesNotFollowAnUnconfiguredLink() throws Exception {
    try (var site = new TestSite(Map.of("/list", page("첫 글", NEXT)))) {
      var rule = new Pagination(PaginationType.NONE, null, null, null, null, null, null, null);
      var result =
          collector(site, new HashMap<>()).execute(job(site.url("/list"), rule), attempt());

      assertThat(result.stopReason()).isEqualTo("END_OF_LIST");
      assertThat(site.requests).containsExactly("/list");
    }
  }

  @Test
  void recognizesRepeatedItemsAtDifferentAddresses() throws Exception {
    try (var site =
        new TestSite(Map.of("/list", page("동일 글", NEXT), "/list?page=2", page("동일 글", "")))) {
      var stored = new HashMap<String, byte[]>();
      var result = collector(site, stored).execute(job(site.url("/list"), nextLink()), attempt());

      assertThat(result.status()).isEqualTo("FAILED");
      assertThat(result.stopReason()).isEqualTo("NAVIGATION_FAILED");
      assertThat(result.snapshotRef()).isNull();
      assertThat(stored).isEmpty();
    }
  }

  @Test
  void identicalRelativeMarkupCanPointToDifferentItemsOnDifferentPaths() throws Exception {
    String row = "<article><h2>공지</h2><a href='detail'>본문</a></article>";
    try (var site =
        new TestSite(
            Map.of("/first/", row + "<a class=next href='/second/'>다음</a>", "/second/", row))) {
      var result =
          collector(site, new HashMap<>()).execute(job(site.url("/first/"), nextLink()), attempt());

      assertThat(result.status()).isEqualTo("SUCCEEDED");
      assertThat(result.listItemCount()).isEqualTo(2);
      assertThat(site.requests).containsExactly("/first/", "/second/");
    }
  }

  @Test
  void disabledParentEndsPaginationButAnActiveControlWithoutDestinationFails() throws Exception {
    try (var site =
        new TestSite(
            Map.of(
                "/disabled",
                    page(
                        "첫 글",
                        "<span aria-disabled=true><a class=next href='/ignored'>다음</a></span>"),
                "/broken", page("첫 글", "<a class=next>다음</a>")))) {
      var collector = collector(site, new HashMap<>());
      assertThat(collector.execute(job(site.url("/disabled"), nextLink()), attempt()).stopReason())
          .isEqualTo("END_OF_LIST");
      assertThat(collector.execute(job(site.url("/broken"), nextLink()), attempt()).stopReason())
          .isEqualTo("NAVIGATION_FAILED");
      assertThat(site.requests).containsExactly("/disabled", "/broken");
    }
  }

  @Test
  void missingDestinationResponseRemainsAFailure() throws Exception {
    try (var site = new TestSite(Map.of("/list", page("첫 글", NEXT)))) {
      var result =
          collector(site, new HashMap<>()).execute(job(site.url("/list"), nextLink()), attempt());

      assertThat(site.requests).containsExactly("/list", "/list?page=2");
      assertThat(result.status()).isEqualTo("FAILED");
      assertThat(result.error().code()).isEqualTo("SITE_UNAVAILABLE");
      assertThat(result.snapshotRef()).isNull();
    }
  }

  @Test
  void blocksTheSecondRequestWhenOwnershipHasBeenLost() throws Exception {
    try (var site =
        new TestSite(Map.of("/list", page("첫 글", NEXT), "/list?page=2", page("둘째 글", "")))) {
      var stored = new HashMap<String, byte[]>();
      var sites =
          site.access((uri, budget) -> {
            if (uri.getQuery() != null) {
              throw new SiteAccessException(SiteAccessException.Kind.CANCELLED, "attempt ended");
            }
            return () -> {};
          });

      assertThatThrownBy(
              () -> collector(sites, stored).execute(job(site.url("/list"), nextLink()), attempt()))
          .isInstanceOf(CancellationException.class);
      assertThat(site.requests).containsExactly("/list");
      assertThat(stored).isEmpty();
    }
  }

  @Test
  void retainsTheCompletedPageWhenTheNextRequestExceedsTheWorkBudget() throws Exception {
    try (var site =
        new TestSite(Map.of("/list", page("첫 글", NEXT), "/list?page=2", page("둘째 글", "")))) {
      var stored = new HashMap<String, byte[]>();
      var sites =
          site.access((uri, budget) -> {
            if (uri.getQuery() != null) {
              throw new SiteAccessException(
                  SiteAccessException.Kind.WORK_LIMIT, "work budget expired");
            }
            return () -> {};
          });

      var result = collector(sites, stored).execute(job(site.url("/list"), nextLink()), attempt());

      assertThat(result.stopReason()).isEqualTo("TIME_LIMIT");
      assertThat(result.status()).isEqualTo("SUCCEEDED");
      assertThat(result.listItemCount()).isEqualTo(1);
      assertThat(
              JsonMapper.shared()
                  .readTree(stored.get(result.snapshotRef().key()))
                  .path("list_snapshots"))
          .hasSize(1);
    }
  }

  @Test
  void twoOfThreeDistinctOccurrencesKeepEntireNextPageAndItsNewValues() throws Exception {
    try (var site =
        new TestSite(
            Map.of(
                "/list", page("같은 값", "") + page("다른 기준", NEXT),
                "/list?page=2",
                    page("같은 값", "") + page("새 값", "<a class=next href='?page=3'>다음</a>")))) {
      var base = job(site.url("/list"), nextLink());
      var full = base.payload().projects().getFirst();
      var stored = new HashMap<String, byte[]>();
      var result =
          collector(site, stored)
              .execute(withProjects(base, incremental(full, "같은 값", "같은 값", "다른 기준")), attempt());

      assertThat(result.stopReason()).isEqualTo("NO_CHANGE");
      assertThat(site.requests).containsExactly("/list", "/list?page=2");
      assertThat(result.projectResults().getFirst().normalExtractedRecordCount()).isEqualTo(4);
      assertThat(result.projectResults().getFirst().canIngest()).isTrue();
      var manifest = JsonMapper.shared().readTree(stored.get(result.snapshotRef().key()));
      assertThat(manifest.path("list_snapshots").get(1).path("items")).hasSize(2);
    }
  }

  @Test
  void normalizesStoredDatetimeBeforeComparison() throws Exception {
    try (var site =
        new TestSite(
            Map.of(
                "/list", page("2026-11-23T00:00:00Z", NEXT),
                "/list?page=2", page("2026-11-24T00:00:00Z", "")))) {
      var base = job(site.url("/list"), nextLink());
      var first = base.payload().projects().getFirst();
      var project =
          new CollectionProject(
              first.projectId(),
              first.projectSourceId(),
              1L,
              1L,
              "INCREMENTAL",
              new Schema(
                  List.of(
                      new SchemaField(
                          FIELD_ID, "date", null, null, null, "datetime", false, null))),
              first.fieldConnectors(),
              List.of(new LatestRecord(Map.of(FIELD_ID, "2026-11-23T09:00:00+09:00"))));
      var stored = new HashMap<String, byte[]>();
      var result = collector(site, stored).execute(withProjects(base, project), attempt());
      assertThat(result.stopReason())
          .withFailMessage(
              "actual=%s, visited=%s, candidate=%s",
              result.stopReason(), site.requests, result.projectResults().getFirst().canIngest())
          .isEqualTo("NO_CHANGE");
      assertThat(site.requests).containsExactly("/list");
      assertThat(result.snapshotRef()).isNull();
    }
  }

  @Test
  void repeatedKnownValuesDoNotBecomeIngestionCandidates() throws Exception {
    try (var site =
        new TestSite(Map.of("/list", page("A", "") + page("B", "") + page("A", NEXT)))) {
      var base = job(site.url("/list"), nextLink());
      var stored = new HashMap<String, byte[]>();
      var result =
          collector(site, stored)
              .execute(
                  withProjects(base, incremental(base.payload().projects().getFirst(), "A", "B")),
                  attempt());

      assertThat(result.stopReason()).isEqualTo("NO_CHANGE");
      assertThat(result.snapshotRef()).isNull();
      assertThat(result.projectResults().getFirst().normalExtractedRecordCount()).isEqualTo(3);
      assertThat(result.projectResults().getFirst().canIngest()).isFalse();
      assertThat(site.requests).containsExactly("/list");
      assertThat(stored).isEmpty();
    }
  }

  @Test
  void fullProjectContinuesAfterIncrementalProjectFinishes() throws Exception {
    try (var site = new TestSite(Map.of("/list", page("A", NEXT), "/list?page=2", page("B", "")))) {
      var base = job(site.url("/list"), nextLink());
      var full = base.payload().projects().getFirst();
      var other = job(site.url("/list"), nextLink()).payload().projects().getFirst();
      var incremental = incremental(other, "A");
      var stored = new HashMap<String, byte[]>();
      var result =
          collector(site, stored).execute(withProjects(base, incremental, full), attempt());

      assertThat(result.stopReason()).isEqualTo("END_OF_LIST");
      assertThat(result.projectResults())
          .extracting(project -> project.normalExtractedRecordCount())
          .containsExactly(1L, 2L);
      assertThat(result.projectResults())
          .extracting(project -> project.canIngest())
          .containsExactly(false, true);
      var manifest = JsonMapper.shared().readTree(stored.get(result.snapshotRef().key()));
      var lastUrls =
          manifest.path("list_snapshots").get(1).path("items").get(0).path("source_urls");
      assertThat(lastUrls).hasSize(1);
      assertThat(lastUrls.get(0).path("project_source_id").asString())
          .isEqualTo(full.projectSourceId());
    }
  }

  @Test
  void reportsEndOfListWhenTheRequestedExtraPageDoesNotExist() throws Exception {
    try (var site = new TestSite(Map.of("/list", page("A", "") + page("B", "")))) {
      var base = job(site.url("/list"), nextLink());
      var result =
          collector(site, new HashMap<>())
              .execute(
                  withProjects(
                      base, incremental(base.payload().projects().getFirst(), "A", "B", "C")),
                  attempt());
      assertThat(result.stopReason()).isEqualTo("END_OF_LIST");
      assertThat(result.snapshotRef()).isNull();
    }
  }

  private static CollectionProject incremental(CollectionProject base, String... values) {
    return new CollectionProject(
        base.projectId(),
        base.projectSourceId(),
        base.schemaVersion(),
        base.connectorRevision(),
        "INCREMENTAL",
        base.schema(),
        base.fieldConnectors(),
        java.util.Arrays.stream(values)
            .map(value -> new LatestRecord(Map.of(FIELD_ID, value)))
            .toList());
  }

  private static Job<CollectionExecute> withProjects(
      Job<CollectionExecute> base, CollectionProject... targets) {
    var payload = base.payload();
    return new Job<>(
        base.id(),
        base.expiresAt(),
        base.workBudget(),
        new CollectionExecute(
            payload.sourceId(),
            "SCHEDULED",
            payload.maxDurationSeconds(),
            payload.navigationRevision(),
            payload.navigation(),
            List.of(targets)));
  }

  private static FixedCollection collector(TestSite site, Map<String, byte[]> stored) {
    return collector(site.access((uri, budget) -> () -> {}), stored);
  }

  private static FixedCollection collector(SiteAccess site, Map<String, byte[]> stored) {
    return new FixedCollection(
        site,
        new FieldValueProcessor(new JavaFieldParser()),
        "artifacts",
        (key, type, bytes) -> stored.put(key, bytes));
  }

  private static String page(String title, String controls) {
    return "<article><h2>" + title + "</h2></article>" + controls;
  }

  private static Pagination nextLink() {
    return new Pagination(PaginationType.NEXT_LINK, ".next", "href", null, null, null, null, null);
  }

  private static Job<CollectionExecute> job(String url, Pagination pagination) {
    var navigation =
        new Navigation(
            url,
            List.of(),
            new Navigation.ListAccess(Navigation.AccessMethod.HTTP, null),
            "article",
            pagination);
    var schema =
        new Schema(
            List.of(new SchemaField(FIELD_ID, "title", null, null, null, "string", false, null)));
    var connector =
        new FieldConnector(
            FIELD_ID,
            FieldConnector.Location.LIST,
            "h2",
            new FieldConnector.Extract(FieldConnector.ExtractType.TEXT, null),
            null,
            null);
    var project =
        new CollectionProject(
            UUID.randomUUID().toString(),
            UUID.randomUUID().toString(),
            1L,
            1L,
            "FULL",
            schema,
            List.of(connector),
            List.of());
    var payload =
        new CollectionExecute(
            UUID.randomUUID().toString(), "INITIAL", 20, 1L, navigation, List.of(project));
    return new Job<>(
        UUID.randomUUID(),
        Instant.now().plusSeconds(30).toString(),
        Duration.ofSeconds(20),
        payload);
  }

  private static AttemptContext attempt() {
    var attempt = mock(AttemptContext.class);
    var started = Instant.now();
    when(attempt.id()).thenReturn(UUID.randomUUID());
    when(attempt.startedAt()).thenReturn(started);
    when(attempt.workDeadline()).thenReturn(started.plusSeconds(20));
    when(attempt.now()).thenAnswer(invocation -> Instant.now());
    return attempt;
  }

  private static final class TestSite implements AutoCloseable {
    private static final String HOST = "fixture.example";
    private final HttpServer server;
    private final InetAddress local;
    private final List<String> requests = new CopyOnWriteArrayList<>();

    private TestSite(Map<String, String> pages) throws IOException {
      local = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
      server = HttpServer.create(new InetSocketAddress(local, 0), 0);
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              String path = exchange.getRequestURI().toString();
              if (!path.equals("/robots.txt")) {
                requests.add(path);
              }
              String body = pages.get(path);
              if (body == null) {
                exchange.sendResponseHeaders(404, -1);
                return;
              }
              byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
              exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes);
            }
          });
      server.start();
    }

    String url(String path) {
      return "http://" + HOST + ":" + server.getAddress().getPort() + path;
    }

    SiteAccess access(RequestCoordinator coordinator) {
      return new SiteAccess(
          coordinator,
          host ->
              host.equals(HOST)
                  ? List.of(local)
                  : PublicUrls.resolvePublic(host, ignored -> List.of(local)));
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
