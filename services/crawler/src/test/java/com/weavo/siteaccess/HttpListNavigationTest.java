package com.weavo.siteaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import com.weavo.crawler.collection.application.FixedCollection;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionExecute;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionProject;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.Schema;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.SchemaField;
import com.weavo.extraction.FieldExtractor;
import com.weavo.extraction.FieldValueProcessor;
import com.weavo.extraction.HtmlScope;
import com.weavo.extraction.JavaFieldParser;
import com.weavo.siteaccess.rule.FieldConnector;
import com.weavo.siteaccess.rule.Navigation;
import com.weavo.siteaccess.rule.Navigation.Pagination;
import com.weavo.siteaccess.rule.Navigation.PaginationType;
import com.weavo.worker.application.AttemptContext;
import com.weavo.worker.application.JobProcessor.Job;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

class HttpListNavigationTest {
  private static final String NEXT = "<a class=next href='?page=2'>다음</a>";
  private static final String FIELD_ID = "95f7d7de-ec00-4fbe-b430-fa3a79763c18";

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
