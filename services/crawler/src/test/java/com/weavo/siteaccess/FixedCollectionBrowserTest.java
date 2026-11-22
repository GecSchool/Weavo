package com.weavo.siteaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import com.weavo.crawler.collection.application.CollectionJobHandlers;
import com.weavo.crawler.collection.application.FixedCollection;
import com.weavo.crawler.collection.domain.rule.FieldConnector;
import com.weavo.crawler.collection.domain.rule.FieldConnector.DetailAccess;
import com.weavo.crawler.collection.domain.rule.FieldConnector.DetailAccessMethod;
import com.weavo.crawler.collection.domain.rule.FieldConnector.DetailNavigation;
import com.weavo.crawler.collection.domain.rule.FieldConnector.DetailOpen;
import com.weavo.crawler.collection.domain.rule.FieldConnector.DetailReturn;
import com.weavo.crawler.collection.domain.rule.FieldConnector.Extract;
import com.weavo.crawler.collection.domain.rule.FieldConnector.ExtractType;
import com.weavo.crawler.collection.domain.rule.FieldConnector.Location;
import com.weavo.crawler.collection.domain.rule.FieldConnector.OpenType;
import com.weavo.crawler.collection.domain.rule.FieldConnector.Parser;
import com.weavo.crawler.collection.domain.rule.FieldConnector.ReturnType;
import com.weavo.crawler.collection.domain.rule.FieldConnector.Target;
import com.weavo.crawler.collection.domain.rule.Navigation;
import com.weavo.crawler.collection.domain.rule.Navigation.AccessMethod;
import com.weavo.crawler.collection.domain.rule.Navigation.ListAccess;
import com.weavo.crawler.collection.domain.rule.Navigation.Pagination;
import com.weavo.crawler.collection.domain.rule.Navigation.PaginationType;
import com.weavo.crawler.collection.infrastructure.messaging.CollectionJobProcessor;
import com.weavo.crawler.collection.infrastructure.messaging.ContractJson;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionCompleted;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionExecute;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionProject;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionProjectResult;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.JobIssueDetected;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.Schema;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.SchemaField;
import com.weavo.extraction.FieldExtractor;
import com.weavo.extraction.FieldValueProcessor;
import com.weavo.extraction.HtmlScope;
import com.weavo.extraction.JavaFieldParser;
import com.weavo.worker.application.AttemptContext;
import com.weavo.worker.application.JobProcessor.Job;
import com.weavo.worker.infrastructure.AwsJobTransport;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfSystemProperty(named = "weavo.browser.smoke", matches = "true")
class FixedCollectionBrowserTest {
  private static final String TITLE = "ba4f9e1e-ae8e-4a3c-9261-dca457848148";
  private static final String BODY = "f1109132-a764-4415-a221-e0757bf0b112";
  private static final String AUTHOR = "c47d2286-10a3-4071-a721-78bd567d6805";
  private static final String BUCKET = "weavo-local-artifacts";
  private static final String LIST =
      """
      <main class="list"><article><h2>첫 글</h2><a href="/detail/1">상세</a>
      <button onclick="location.href='/detail/1'">열기</button></article>
      <article><h2>둘째 글</h2><a href="/detail/2">상세</a>
      <button onclick="location.href='/detail/2'">열기</button></article></main>
      """;

  @Test
  void browserDetailLinkCanBeReplayedFromTheStoredHttpListAndDetail() throws Exception {
    try (var site = new TestSite(LIST)) {
      var stored = new HashMap<String, byte[]>();
      var execution = job(site.url("/list"), AccessMethod.HTTP, OpenType.LINK);
      var attempt = attempt(Duration.ofSeconds(20));
      var output =
          collection(site.access(), (key, type, bytes) -> stored.put(key, bytes))
              .execute(execution, attempt);

      assertThat(output.status()).isEqualTo("SUCCEEDED");
      assertThat(output.stopReason()).isEqualTo("END_OF_LIST");
      assertThat(output.projectResults())
          .extracting(CollectionProjectResult::normalExtractedRecordCount)
          .containsExactly(2L, 2L);
      assertThat(stored).hasSize(4);
      assertThat(site.detailRequests.get()).isEqualTo(2);
      var manifest = read(stored.get(output.snapshotRef().key()));
      assertThat(manifest.path("attempt_id").asString()).isEqualTo(attempt.id().toString());
      assertThat(manifest.path("collection_job_id").asString())
          .isEqualTo(execution.id().toString());
      var list = manifest.path("list_snapshots").get(0);
      assertThat(list.path("access_method").asString()).isEqualTo("HTTP");
      var listHtml = HtmlScope.parse(html(stored.get(key(list))));
      var titles = new ArrayList<String>();
      for (var item : list.path("items")) {
        int index = item.path("item_index").asInt();
        assertThat(item.path("sequence_index").asInt()).isEqualTo(index);
        assertThat(item.path("record_key").asString())
            .isEqualTo("list-0001:item-%04d".formatted(index));
        titles.add(text(listHtml.selectItem(list.path("item_selector").asString(), index), "h2"));
        var detail = item.path("details").get(0);
        assertThat(detail.path("consumers")).hasSize(2);
        assertThat(detail.path("consumers").get(0).path("field_ids").get(0).asString())
            .isEqualTo(BODY);
        assertThat(detail.path("consumers").get(1).path("field_ids").get(0).asString())
            .isEqualTo(AUTHOR);
        var detailScope = HtmlScope.parse(html(stored.get(key(detail))));
        assertThat(text(detailScope, ".body")).isEqualTo("본문 " + (index + 1));
        assertThat(text(detailScope, ".author")).isEqualTo("작성자");
        assertThat(item.path("source_urls").get(0).path("source_url").asString())
            .isEqualTo(site.url("/detail/" + (index + 1)));
      }
      assertThat(titles).containsExactly("첫 글", "둘째 글");
    }
  }

  @Test
  void sourceUrlRemainsTheWorkingLinkWhenTheDetailPageReplacesItsAddress() throws Exception {
    try (var site = new TestSite(LIST)) {
      site.detailReplacesUrl = true;
      var stored = new HashMap<String, byte[]>();
      var access = site.access();
      var output =
          collection(access, (key, type, bytes) -> stored.put(key, bytes))
              .execute(
                  job(site.url("/list"), AccessMethod.HTTP, OpenType.LINK),
                  attempt(Duration.ofSeconds(20)));

      assertThat(output.status()).isEqualTo("SUCCEEDED");
      var item =
          read(stored.get(output.snapshotRef().key()))
              .path("list_snapshots")
              .get(0)
              .path("items")
              .get(0);
      var directUrl = item.path("source_urls").get(0).path("source_url").asString();
      assertThat(directUrl).isEqualTo(site.url("/detail/1"));
      assertThat(item.path("details").get(0).path("url").asString())
          .isEqualTo(site.url("/virtual/1"));
      var reopened =
          access
              .open(new WorkBudget(Instant.now().plusSeconds(5), () -> false, deadline -> {}))
              .fetchHtml(directUrl);
      assertThat(text(HtmlScope.parse(reopened.html()), ".body")).isEqualTo("본문 1");
    }
  }

  @Test
  void cosmeticMarkupChangesAfterReturningKeepTheOriginalLinkAssociations() throws Exception {
    try (var site = new TestSite(LIST)) {
      site.cosmeticChangeAfterDetail = true;
      var stored = new HashMap<String, byte[]>();
      var output =
          collection(site.access(), (key, type, bytes) -> stored.put(key, bytes))
              .execute(
                  job(site.url("/list"), AccessMethod.BROWSER, OpenType.LINK),
                  attempt(Duration.ofSeconds(20)));

      assertThat(output.status()).isEqualTo("SUCCEEDED");
      assertThat(site.detailRequests.get()).isEqualTo(2);
      var items =
          read(stored.get(output.snapshotRef().key())).path("list_snapshots").get(0).path("items");
      for (var item : items) {
        int index = item.path("item_index").asInt();
        var detail = HtmlScope.parse(html(stored.get(key(item.path("details").get(0)))));
        assertThat(text(detail, ".body")).isEqualTo("본문 " + (index + 1));
      }
    }
  }

  @Test
  void aChangedDetailLinkIsRejectedEvenWhenTheItemTextIsTheSame() throws Exception {
    try (var site = new TestSite(LIST)) {
      site.linkChangesAfterDetail = true;
      var stored = new HashMap<String, byte[]>();
      var output =
          collection(site.access(), (key, type, bytes) -> stored.put(key, bytes))
              .execute(
                  job(site.url("/list"), AccessMethod.BROWSER, OpenType.LINK),
                  attempt(Duration.ofSeconds(20)));

      assertThat(output.stopReason()).isEqualTo("NAVIGATION_FAILED");
      assertThat(output.error().stage()).isEqualTo("DETAIL_RETURN");
      assertThat(output.snapshotRef()).isNull();
      assertThat(stored).isEmpty();
    }
  }

  @Test
  void replayRejectsAMissingSelectorAndOutOfRangeItemIndexWithoutSubstitutingARow()
      throws Exception {
    try (var site = new TestSite(LIST)) {
      var stored = new HashMap<String, byte[]>();
      var output =
          collection(site.access(), (key, type, bytes) -> stored.put(key, bytes))
              .execute(
                  job(site.url("/list"), AccessMethod.HTTP, OpenType.LINK),
                  attempt(Duration.ofSeconds(20)));

      var list = read(stored.get(output.snapshotRef().key())).path("list_snapshots").get(0);
      var document = HtmlScope.parse(html(stored.get(key(list))));
      String selector = list.path("item_selector").asString();
      int originalIndex = list.path("items").get(1).path("item_index").asInt();
      assertThat(text(document.selectItem(selector, originalIndex), "h2")).isEqualTo("둘째 글");
      assertThatThrownBy(() -> document.selectItem(selector, originalIndex + 1))
          .isInstanceOf(IllegalArgumentException.class);
      assertThatThrownBy(() -> document.selectItem(selector + ".missing", originalIndex))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"/lists/", "/details/", "/manifest.json"})
  void snapshotUploadFailureProducesAStorableCompletionWithoutAnIngestibleReference(
      String rejectedPath) throws Exception {
    try (var site = new TestSite(LIST)) {
      var stored = new HashMap<String, byte[]>();
      var contract = new ContractJson();
      var processor =
          new CollectionJobProcessor(
              contract,
              CollectionJobHandlers.using(
                  collection(
                      site.access(),
                      (key, mime, bytes) -> {
                        if (key.contains(rejectedPath)) {
                          throw SdkClientException.create("Snapshot write failed");
                        }
                        stored.put(key, bytes);
                      })));
      var result =
          processor.execute(
              job(site.url("/list"), AccessMethod.HTTP, OpenType.LINK),
              attempt(Duration.ofSeconds(20)));

      assertThat(result.objectKey()).endsWith("/result.json");
      var completed =
          contract.eventPayload(contract.readEvent(result.body()), CollectionCompleted.class);
      assertThat(completed.status()).isEqualTo("FAILED");
      assertThat(completed.stopReason()).isEqualTo("TECHNICAL_FAILURE");
      assertThat(completed.error().code()).isEqualTo("SNAPSHOT_WRITE_FAILED");
      assertThat(completed.snapshotRef()).isNull();
      assertThat(completed.projectResults())
          .allSatisfy(project -> {
            assertThat(project.canIngest()).isFalse();
            assertThat(project.error().code()).isEqualTo("SNAPSHOT_WRITE_FAILED");
          });
      assertThat(stored.keySet()).noneMatch(key -> key.endsWith("/manifest.json"));
    }
  }

  @Test
  void clickDetailsRemainReplayableWithoutAnApprovedDirectUrl() throws Exception {
    try (var site = new TestSite(LIST)) {
      var stored = new HashMap<String, byte[]>();
      var output =
          collection(site.access(), (key, type, bytes) -> stored.put(key, bytes))
              .execute(
                  job(site.url("/list"), AccessMethod.BROWSER, OpenType.CLICK),
                  attempt(Duration.ofSeconds(20)));

      assertThat(output.status()).isEqualTo("SUCCEEDED");
      var list = read(stored.get(output.snapshotRef().key())).path("list_snapshots").get(0);
      var item = list.path("items").get(0);
      assertThat(item.path("source_urls").get(0).path("source_url").isNull()).isTrue();
      assertThat(text(HtmlScope.parse(html(stored.get(key(item.path("details").get(0))))), ".body"))
          .isEqualTo("본문 1");
    }
  }

  @Test
  void emptyListProducesASourceIssueAndFailedCompletionWithoutAReference() throws Exception {
    try (var site = new TestSite("<main class=list></main>")) {
      var stored = new HashMap<String, byte[]>();
      var contract = new ContractJson();
      var processor =
          new CollectionJobProcessor(
              contract,
              CollectionJobHandlers.using(
                  collection(site.access(), (key, mime, bytes) -> stored.put(key, bytes))));
      var execution = job(site.url("/list"), AccessMethod.HTTP, OpenType.LINK);
      var attempt = attempt(Duration.ofSeconds(20));
      var result = processor.execute(execution, attempt);
      var completed =
          contract.eventPayload(contract.readEvent(result.body()), CollectionCompleted.class);

      assertThat(completed.listItemCount()).isZero();
      assertThat(completed.stopReason()).isEqualTo("EMPTY_LIST");
      assertThat(completed.snapshotRef()).isNull();
      assertThat(stored).isEmpty();
      assertThat(result.notices()).hasSize(1);
      var issue =
          contract.eventPayload(
              contract.readEvent(result.notices().getFirst()), JobIssueDetected.class);
      assertThat(issue.scope()).isEqualTo("SOURCE");
      assertThat(issue.attemptId()).isEqualTo(attempt.id().toString());
      assertThat(issue.error().code()).isEqualTo("EMPTY_LIST");
    }
  }

  @Test
  void changedListAfterHistoryReturnNeverPublishesTheWrongItem() throws Exception {
    try (var site = new TestSite(LIST)) {
      site.reorderAfterDetail = true;
      var stored = new HashMap<String, byte[]>();
      var output =
          collection(site.access(), (key, type, bytes) -> stored.put(key, bytes))
              .execute(
                  job(site.url("/list"), AccessMethod.BROWSER, OpenType.LINK),
                  attempt(Duration.ofSeconds(20)));

      assertThat(output.status()).isEqualTo("FAILED");
      assertThat(output.stopReason()).isEqualTo("NAVIGATION_FAILED");
      assertThat(output.error().stage()).isEqualTo("DETAIL_RETURN");
      assertThat(output.snapshotRef()).isNull();
      assertThat(stored).isEmpty();
    }
  }

  @Test
  void detailOperationTimeoutReportsNavigationFailure() throws Exception {
    try (var site = new TestSite(LIST)) {
      var siteAccess =
          site.access((target, budget) -> {
            if (target.getPath().equals("/detail/1")) {
              throw new SiteAccessException(
                  SiteAccessException.Kind.BROWSER_TIMEOUT, "Detail wait expired");
            }
            return () -> {};
          });
      var stored = new HashMap<String, byte[]>();
      var result =
          collection(siteAccess, (key, type, bytes) -> stored.put(key, bytes))
              .execute(
                  job(site.url("/list"), AccessMethod.BROWSER, OpenType.LINK),
                  attempt(Duration.ofSeconds(20)));

      assertThat(result.stopReason()).isEqualTo("NAVIGATION_FAILED");
      assertThat(result.error().stage()).isEqualTo("DETAIL_NAVIGATION");
      assertThat(result.snapshotRef()).isNull();
      assertThat(stored).isEmpty();
    }
  }

  @Test
  void budgetExpirySavesOnlyCompleteItemsAndUsesTheCompletionWindow() throws Exception {
    try (var site = new TestSite(LIST)) {
      var stored = new HashMap<String, byte[]>();
      var siteAccess =
          site.access((target, budget) -> {
            if (target.getPath().equals("/detail/2")) {
              throw new SiteAccessException(
                  SiteAccessException.Kind.WORK_LIMIT, "Work budget expired");
            }
            return () -> {};
          });
      var result =
          collection(siteAccess, (key, type, bytes) -> stored.put(key, bytes))
              .execute(
                  job(site.url("/list"), AccessMethod.BROWSER, OpenType.LINK),
                  attempt(Duration.ofSeconds(20)));

      assertThat(result.status()).isEqualTo("SUCCEEDED");
      assertThat(result.stopReason()).isEqualTo("TIME_LIMIT");
      assertThat(result.listItemCount()).isEqualTo(2);
      assertThat(result.projectResults())
          .extracting(CollectionProjectResult::normalExtractedRecordCount)
          .containsExactly(1L, 1L);
      assertThat(stored).hasSize(3);
      assertThat(site.detailRequests.get()).isEqualTo(1);
      assertThat(
              read(stored.get(result.snapshotRef().key()))
                  .path("list_snapshots")
                  .get(0)
                  .path("items"))
          .hasSize(1);
    }
  }

  @Test
  void losingTheLeaseBeforeUploadLeavesTheSnapshotUnpublished() throws Exception {
    try (var site = new TestSite(LIST)) {
      var stored = new HashMap<String, byte[]>();
      var attempt = attempt(Duration.ofSeconds(20));
      doThrow(new CancellationException("lease lost")).when(attempt).requireOwnership();

      assertThatThrownBy(() ->
              collection(site.access(), (key, type, bytes) -> stored.put(key, bytes))
                  .execute(job(site.url("/list"), AccessMethod.BROWSER, OpenType.LINK), attempt))
          .isInstanceOf(CancellationException.class);
      assertThat(stored).isEmpty();
    }
  }

  @Test
  void deniedListIsAnAccessFailureInsteadOfAnEmptyList() throws Exception {
    try (var site = new TestSite(LIST)) {
      site.robotsDenied = true;
      var result =
          collection(
                  site.access(),
                  (key, type, bytes) -> {
                    throw new AssertionError("Blocked list must not reach S3");
                  })
              .execute(
                  job(site.url("/list"), AccessMethod.HTTP, OpenType.LINK),
                  attempt(Duration.ofSeconds(20)));

      assertThat(result.stopReason()).isEqualTo("ACCESS_BLOCKED");
      assertThat(result.listItemCount()).isNull();
      assertThat(result.snapshotRef()).isNull();
      assertThat(result.error().code()).isEqualTo("ACCESS_BLOCKED");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  @EnabledIfEnvironmentVariable(named = "WEAVO_SNAPSHOT_TEST_S3", matches = ".+")
  void storesAndReopensTheResultInLocalStack(boolean rejectSnapshot) throws Exception {
    var endpoint = URI.create(System.getenv("WEAVO_SNAPSHOT_TEST_S3"));
    try (var site = new TestSite(LIST);
        var transport =
            AwsJobTransport.open(
                "ap-northeast-2",
                endpoint,
                BUCKET,
                List.of("collection-priority-jobs"),
                "control-results",
                Duration.ofSeconds(5));
        var s3 =
            S3Client.builder()
                .endpointOverride(endpoint)
                .region(Region.AP_NORTHEAST_2)
                .credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .serviceConfiguration(
                    S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .build()) {
      var contract = new ContractJson();
      var processor =
          new CollectionJobProcessor(
              contract,
              CollectionJobHandlers.using(
                  collection(
                      site.access(),
                      (key, mime, bytes) -> {
                        if (rejectSnapshot) {
                          throw SdkClientException.create("Only snapshot objects are unavailable");
                        }
                        transport.storeObject(key, mime, bytes);
                      })));
      var result =
          processor.execute(
              job(site.url("/list"), AccessMethod.HTTP, OpenType.LINK),
              attempt(Duration.ofSeconds(20)));

      var keys = new ArrayList<String>();
      try {
        keys.add(result.objectKey());
        transport.store(result.objectKey(), result.body());
        var completed =
            contract.eventPayload(
                contract.readEvent(html(s3Bytes(s3, result.objectKey()))),
                CollectionCompleted.class);
        if (rejectSnapshot) {
          assertThat(completed.error().code()).isEqualTo("SNAPSHOT_WRITE_FAILED");
          assertThat(completed.snapshotRef()).isNull();
          return;
        }

        assertThat(completed.status()).isEqualTo("SUCCEEDED");
        keys.add(completed.snapshotRef().key());
        var list = read(s3Bytes(s3, completed.snapshotRef().key())).path("list_snapshots").get(0);
        keys.add(key(list));
        for (var item : list.path("items")) {
          keys.add(key(item.path("details").get(0)));
        }
        var document = HtmlScope.parse(html(s3Bytes(s3, key(list))));
        String selector = list.path("item_selector").asString();
        for (var item : list.path("items")) {
          int index = item.path("item_index").asInt();
          assertThat(text(document.selectItem(selector, index), "h2"))
              .isEqualTo(index == 0 ? "첫 글" : "둘째 글");
          var detail = HtmlScope.parse(html(s3Bytes(s3, key(item.path("details").get(0)))));
          assertThat(text(detail, ".body")).isEqualTo("본문 " + (index + 1));
        }
      } finally {
        for (String key : keys) {
          s3.deleteObject(request -> request.bucket(BUCKET).key(key));
        }
      }
    }
  }

  private static byte[] s3Bytes(S3Client s3, String key) {
    return s3.getObjectAsBytes(request -> request.bucket(BUCKET).key(key)).asByteArray();
  }

  private static String key(JsonNode page) {
    return page.path("object_ref").path("key").asString();
  }

  private static String html(byte[] bytes) {
    return new String(bytes, StandardCharsets.UTF_8);
  }

  private static JsonNode read(byte[] bytes) {
    return JsonMapper.shared().readTree(bytes);
  }

  private static String text(HtmlScope scope, String selector) {
    var result = FieldExtractor.extractText(scope, selector);
    return result.rawValues().getFirst();
  }

  private static FixedCollection collection(
      SiteAccess site, FixedCollection.SnapshotUpload upload) {
    return new FixedCollection(
        site, new FieldValueProcessor(new JavaFieldParser()), BUCKET, upload);
  }

  private static AttemptContext attempt(Duration work) {
    var attempt = mock(AttemptContext.class);
    var started = Instant.now();
    when(attempt.id()).thenReturn(UUID.randomUUID());
    when(attempt.startedAt()).thenReturn(started);
    when(attempt.workDeadline()).thenReturn(started.plus(work));
    when(attempt.now()).thenAnswer(invocation -> Instant.now());
    return attempt;
  }

  private static Job<CollectionExecute> job(String url, AccessMethod method, OpenType openType) {
    var navigation =
        new Navigation(
            url,
            List.of(),
            new ListAccess(method, null),
            "article",
            new Pagination(PaginationType.NONE, null, null, null, null, null, null, null));
    var detail =
        new DetailNavigation(
            new DetailAccess(DetailAccessMethod.BROWSER, ".body"),
            new DetailOpen(
                openType,
                openType == OpenType.LINK ? "a" : "button",
                openType == OpenType.LINK ? "href" : null,
                Target.CURRENT_TAB),
            new DetailReturn(ReturnType.HISTORY_BACK, ".list", null));
    var request =
        new CollectionExecute(
            UUID.randomUUID().toString(),
            "INITIAL",
            20,
            4L,
            navigation,
            List.of(project(BODY, ".body", detail), project(AUTHOR, ".author", detail)));
    return new Job<>(
        UUID.randomUUID(),
        Instant.now().plusSeconds(30).toString(),
        Duration.ofSeconds(20),
        request);
  }

  private static CollectionProject project(
      String fieldId, String selector, DetailNavigation detail) {
    var schema =
        new Schema(
            List.of(
                new SchemaField(TITLE, "title", null, null, null, "string", false, null),
                new SchemaField(fieldId, "detail", null, null, null, "string", false, null)));
    var text = new Extract(ExtractType.TEXT, null);
    var connectors =
        List.of(
            new FieldConnector(
                TITLE, Location.LIST, "h2", text, new Parser("return rawValues.getFirst();"), null),
            new FieldConnector(fieldId, Location.DETAIL, selector, text, null, detail));
    return new CollectionProject(
        UUID.randomUUID().toString(),
        UUID.randomUUID().toString(),
        1L,
        2L,
        "FULL",
        schema,
        connectors,
        List.of());
  }

  private static final class TestSite implements AutoCloseable {
    private final HttpServer server;
    private final InetAddress local;
    private final AtomicInteger detailRequests = new AtomicInteger();
    private volatile boolean reorderAfterDetail;
    private volatile boolean detailReplacesUrl;
    private volatile boolean cosmeticChangeAfterDetail;
    private volatile boolean linkChangesAfterDetail;
    private volatile boolean robotsDenied;

    TestSite(String list) throws IOException {
      local = InetAddress.getByAddress(new byte[] {127, 0, 0, 1});
      server = HttpServer.create(new InetSocketAddress(local, 0), 0);
      server.createContext(
          "/",
          exchange -> {
            try (exchange) {
              var path = exchange.getRequestURI().getPath();
              if (path.equals("/robots.txt")) {
                if (robotsDenied) {
                  byte[] robots =
                      "User-agent: *\nDisallow: /list\n".getBytes(StandardCharsets.UTF_8);
                  exchange.sendResponseHeaders(200, robots.length);
                  exchange.getResponseBody().write(robots);
                } else {
                  exchange.sendResponseHeaders(404, -1);
                }
                return;
              }
              exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
              exchange.getResponseHeaders().set("Cache-Control", "no-store");
              final String body;
              if (path.startsWith("/detail/")) {
                detailRequests.incrementAndGet();
                var item = path.substring("/detail/".length());
                body =
                    "<div class=body>본문 "
                        + item
                        + "</div><span class=author>작성자</span>"
                        + (detailReplacesUrl
                            ? "<script>history.replaceState(null, '', '/virtual/"
                                + item
                                + "');</script>"
                            : "");
              } else {
                body = listResponse(list);
              }
              byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
              exchange.sendResponseHeaders(200, bytes.length);
              exchange.getResponseBody().write(bytes);
            }
          });
      server.start();
    }

    private String listResponse(String list) {
      if (detailRequests.get() == 0) {
        return list;
      }
      if (reorderAfterDetail) {
        return list.replace("첫 글", "바뀐 항목");
      }
      if (cosmeticChangeAfterDetail) {
        return list.replace("<article>", "<article data-view=history>");
      }
      if (linkChangesAfterDetail) {
        return list.replace("href=\"/detail/1\"", "href=\"/detail/3\"");
      }
      return list;
    }

    String url(String path) {
      return "http://fixture.example:" + server.getAddress().getPort() + path;
    }

    SiteAccess access() {
      return access((host, budget) -> () -> {});
    }

    SiteAccess access(RequestCoordinator coordinator) {
      return new SiteAccess(
          coordinator,
          host ->
              host.equals("fixture.example")
                  ? List.of(local)
                  : PublicUrls.resolvePublic(host, ignored -> List.of(local)));
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
