package com.weavo.siteaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.sun.net.httpserver.HttpServer;
import com.weavo.crawler.collection.application.CollectionJobHandlers;
import com.weavo.crawler.collection.application.FixedCollection;
import com.weavo.crawler.collection.infrastructure.messaging.CollectionJobProcessor;
import com.weavo.crawler.collection.infrastructure.messaging.ContractJson;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionCompleted;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionExecute;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionProject;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionProjectResult;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.JobIssueDetected;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.LatestRecord;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.Schema;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.SchemaField;
import com.weavo.extraction.FieldExtractor;
import com.weavo.extraction.FieldValueProcessor;
import com.weavo.extraction.HtmlScope;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
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
  void equivalentLinkSelectorsShareADetailAndOnlyActiveProjectsConsumeLaterItems()
      throws Exception {
    String first =
        """
        <main class=list><article><h2>첫 글</h2><a class=alternate href=/detail/1>상세</a></article></main>
        <a class=next href=/next>다음</a>
        """;
    String second =
        """
        <main class=list><article><h2>둘째 글</h2><a class=alternate href=/detail/2>상세</a></article></main>
        """;
    try (var site = new TestSite(first, Map.of("/next", second))) {
      var base = job(site.url("/list"), AccessMethod.BROWSER, OpenType.LINK);
      var payload = base.payload();
      var rule =
          base.payload().projects().getFirst().fieldConnectors().getLast().detailNavigation();
      var altRule =
          new DetailNavigation(
              rule.access(),
              new DetailOpen(OpenType.LINK, ".alternate", "href", Target.CURRENT_TAB),
              rule.returnRule());
      var old = base.payload().projects().getFirst();
      var fast =
          new CollectionProject(
              old.projectId(),
              old.projectSourceId(),
              old.schemaVersion(),
              old.connectorRevision(),
              "INCREMENTAL",
              old.schema(),
              old.fieldConnectors(),
              List.of(new LatestRecord(Map.of(TITLE, "첫 글", BODY, "본문 1"))));
      var full = project(AUTHOR, ".author", altRule);
      var navigation =
          browserList(
              site.url("/list"),
              new Pagination(
                  PaginationType.NEXT_LINK, ".next", "href", ".list", null, null, null, null));
      navigation =
          new Navigation(
              navigation.listUrl(),
              List.of(),
              new ListAccess(AccessMethod.BROWSER, ".list"),
              navigation.itemSelector(),
              navigation.pagination());
      var request =
          new CollectionExecute(
              payload.sourceId(), "SCHEDULED", 20, 4L, navigation, List.of(fast, full));
      var execution = new Job<>(base.id(), base.expiresAt(), base.workBudget(), request);
      var stored = new HashMap<String, byte[]>();
      var result =
          collection(site.access(), (key, type, bytes) -> stored.put(key, bytes))
              .execute(execution, attempt(Duration.ofSeconds(15)));

      assertThat(result.status()).withFailMessage("%s", result.error()).isEqualTo("SUCCEEDED");
      var pages = read(stored.get(result.snapshotRef().key())).path("list_snapshots");
      var firstItem = pages.get(0).path("items").get(0);
      assertThat(firstItem.path("details")).hasSize(1);
      assertThat(firstItem.path("details").get(0).path("consumers")).hasSize(2);
      var laterItem = pages.get(1).path("items").get(0);
      assertThat(laterItem.path("source_urls")).hasSize(1);
      assertThat(laterItem.path("source_urls").get(0).path("project_source_id").asString())
          .isEqualTo(full.projectSourceId());
      assertThat(laterItem.path("details").get(0).path("consumers")).hasSize(1);
      assertThat(
              laterItem
                  .path("details")
                  .get(0)
                  .path("consumers")
                  .get(0)
                  .path("project_source_id")
                  .asString())
          .isEqualTo(full.projectSourceId());
      assertThat(site.detailRequests.get()).isEqualTo(2);
    }
  }

  @Test
  void skipsBrowserOnHttpPageWhenOnlyListProjectsRemain() throws Exception {
    String first =
        "<main class=list><article><h2>첫 글</h2><a href=/detail/1>상세</a></article>"
            + "<a class=next href=/next>다음</a></main>";
    String second =
        "<main class=list><article><h2>둘째 글</h2></article></main>"
            + "<script>document.querySelector('h2').textContent = 'rendered variant';</script>";
    try (var site = new TestSite(first, Map.of("/next", second))) {
      var base = job(site.url("/list"), AccessMethod.HTTP, OpenType.LINK);
      var payload = base.payload();
      var old = payload.projects().getFirst();
      var fast =
          new CollectionProject(
              old.projectId(),
              old.projectSourceId(),
              1L,
              2L,
              "INCREMENTAL",
              old.schema(),
              old.fieldConnectors(),
              List.of(new LatestRecord(Map.of(TITLE, "첫 글", BODY, "본문 1"))));
      var navigation =
          new Navigation(
              site.url("/list"),
              List.of(),
              new ListAccess(AccessMethod.HTTP, null),
              "article",
              new Pagination(
                  PaginationType.NEXT_LINK, ".next", "href", null, null, null, null, null));
      var full = listOnlyJob(navigation).payload().projects().getFirst();
      var request =
          new CollectionExecute(
              payload.sourceId(), "SCHEDULED", 20, 4L, navigation, List.of(fast, full));
      var stored = new HashMap<String, byte[]>();
      var result =
          collection(site.access(), (key, mime, bytes) -> stored.put(key, bytes))
              .execute(
                  new Job<>(base.id(), base.expiresAt(), base.workBudget(), request),
                  attempt(Duration.ofSeconds(15)));
      assertThat(result.status())
          .withFailMessage(
              "status=%s, error=%s, requests=%s", result.status(), result.error(), site.requests)
          .isEqualTo("SUCCEEDED");
      assertThat(result.projectResults().getLast().normalExtractedRecordCount()).isEqualTo(2L);
    }
  }

  @Test
  void differentModalControlsKeepSeparateDetailObjectsAtTheSameAddress() throws Exception {
    try (var site = new TestSite(modalList("this.closest('dialog').close()", false))) {
      var base = job(site.url("/list"), AccessMethod.BROWSER, OpenType.CLICK, Target.MODAL);
      var payload = base.payload();
      var rule = payload.projects().getFirst().fieldConnectors().getLast().detailNavigation();
      var alternate =
          project(
              AUTHOR,
              ".author",
              new DetailNavigation(
                  rule.access(),
                  new DetailOpen(OpenType.CLICK, "a", null, Target.MODAL),
                  rule.returnRule()));
      var request =
          new CollectionExecute(
              payload.sourceId(),
              payload.executionReason(),
              20,
              4L,
              payload.navigation(),
              List.of(payload.projects().getFirst(), alternate));
      var stored = new HashMap<String, byte[]>();
      var result =
          collection(site.access(), (key, type, bytes) -> stored.put(key, bytes))
              .execute(
                  new Job<>(base.id(), base.expiresAt(), base.workBudget(), request),
                  attempt(Duration.ofSeconds(15)));

      assertThat(result.status()).withFailMessage("%s", result.error()).isEqualTo("SUCCEEDED");
      var details =
          read(stored.get(result.snapshotRef().key()))
              .path("list_snapshots")
              .get(0)
              .path("items")
              .get(0)
              .path("details");
      assertThat(details).hasSize(2);
      assertThat(details.get(0).path("url").asString())
          .isEqualTo(details.get(1).path("url").asString());
      assertThat(key(details.get(0))).isNotEqualTo(key(details.get(1)));
      assertThat(details.get(0).path("consumers")).hasSize(1);
      assertThat(details.get(1).path("consumers")).hasSize(1);
    }
  }

  @Test
  void missingDetailControlStopsOnlyItsProjectAndKeepsTheListUsable() throws Exception {
    try (var site = new TestSite(LIST)) {
      var base = job(site.url("/list"), AccessMethod.BROWSER, OpenType.LINK);
      var payload = base.payload();
      var normal = payload.projects().getLast();
      var rule = normal.fieldConnectors().getLast().detailNavigation();
      var broken =
          project(
              BODY,
              ".body",
              new DetailNavigation(
                  rule.access(),
                  new DetailOpen(OpenType.LINK, ".missing", "href", Target.CURRENT_TAB),
                  rule.returnRule()));
      var request =
          new CollectionExecute(
              payload.sourceId(), "INITIAL", 20, 4L, payload.navigation(), List.of(broken, normal));
      var stored = new HashMap<String, byte[]>();
      var result =
          collection(site.access(), (key, mime, bytes) -> stored.put(key, bytes))
              .execute(
                  new Job<>(base.id(), base.expiresAt(), base.workBudget(), request),
                  attempt(Duration.ofSeconds(15)));
      assertThat(result.status()).withFailMessage("%s", result.error()).isEqualTo("PARTIAL");
      assertThat(result.projectResults().getFirst().error().stage()).isEqualTo("DETAIL_NAVIGATION");
      assertThat(result.projectResults().getLast().canIngest()).isTrue();
      assertThat(site.detailRequests.get()).isEqualTo(2);
    }
  }

  @Test
  void missingModalButtonOnlyStopsItsProject() throws Exception {
    try (var site = new TestSite(modalList("this.closest('dialog').close()", false))) {
      var base = job(site.url("/list"), AccessMethod.BROWSER, OpenType.CLICK, Target.MODAL);
      var payload = base.payload();
      var normal = payload.projects().getFirst();
      var rule = normal.fieldConnectors().getLast().detailNavigation();
      var broken =
          project(
              AUTHOR,
              ".author",
              new DetailNavigation(
                  rule.access(),
                  new DetailOpen(OpenType.CLICK, ".missing", null, Target.MODAL),
                  rule.returnRule()));
      var request =
          new CollectionExecute(
              payload.sourceId(), "INITIAL", 20, 4L, payload.navigation(), List.of(broken, normal));
      var stored = new HashMap<String, byte[]>();
      var result =
          collection(site.access(), (key, mime, bytes) -> stored.put(key, bytes))
              .execute(
                  new Job<>(base.id(), base.expiresAt(), base.workBudget(), request),
                  attempt(Duration.ofSeconds(15)));
      assertThat(result.status())
          .withFailMessage("actual status=%s, top-level error=%s", result.status(), result.error())
          .isEqualTo("PARTIAL");
      assertThat(result.projectResults().getLast().canIngest()).isTrue();
    }
  }

  @Test
  void completedPreservesDetailFailureFieldsWhenIssueArrivesLater() throws Exception {
    try (var site = new TestSite(LIST)) {
      var base = job(site.url("/list"), AccessMethod.BROWSER, OpenType.LINK);
      var payload = base.payload();
      var good = payload.projects().getLast();
      var rule = good.fieldConnectors().getLast().detailNavigation();
      var broken =
          project(
              BODY,
              ".body",
              new DetailNavigation(
                  rule.access(),
                  new DetailOpen(OpenType.LINK, ".missing", "href", Target.CURRENT_TAB),
                  rule.returnRule()));
      var request =
          new CollectionExecute(
              payload.sourceId(), "INITIAL", 20, 4L, payload.navigation(), List.of(broken, good));
      var contract = new ContractJson();
      var processor =
          new CollectionJobProcessor(
              contract,
              CollectionJobHandlers.using(collection(site.access(), (key, mime, bytes) -> {})));
      var output =
          processor.execute(
              new Job<>(base.id(), base.expiresAt(), base.workBudget(), request),
              attempt(Duration.ofSeconds(15)));
      var result =
          contract.eventPayload(contract.readEvent(output.body()), CollectionCompleted.class);
      var issue =
          contract.eventPayload(
              contract.readEvent(output.notices().getFirst()), JobIssueDetected.class);
      assertThat(issue.fieldIds()).containsExactly(BODY);
      var completedError = result.projectResults().getFirst().error();
      assertThat(completedError.details()).containsEntry("field_ids", issue.fieldIds());
      assertThat(completedError).isEqualTo(issue.error());
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

  @Test
  void appliesInitialClickThenNavigatesInTheSameDocument() throws Exception {
    String first =
        """
        <button class=initial onclick="document.querySelector('main').hidden=false;
          document.querySelector('main').className='ready'; this.remove()">목록</button>
        <main hidden><article><h2>첫 번째</h2></article>
        <button class=next onclick="document.querySelector('h2').textContent='두 번째'; this.disabled=true">다음</button>
        </main>
        """;
    try (var site = new TestSite(first)) {
      var navigation =
          new Navigation(
              site.url("/list"),
              List.of(
                  new Navigation.InitialAction(Navigation.ActionType.CLICK, ".initial", ".ready")),
              new ListAccess(AccessMethod.BROWSER, ".ready"),
              "article",
              new Pagination(
                  PaginationType.CLICK_NEXT, ".next", null, ".ready", null, null, null, null));
      var stored = new HashMap<String, byte[]>();
      assertBrowserLists(site, navigation, stored, "첫 번째", "두 번째");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void rendersBothPagesForUrlBasedBrowserRules(boolean template) throws Exception {
    try (var site =
        new TestSite(
            "",
            Map.of(
                "/list?page=1", dynamicScreenshot("첫 번째", "?page=2"),
                "/list?page=2", dynamicScreenshot("두 번째", null),
                "/list?page=3", "<main class=ready></main>"))) {
      var rule =
          template
              ? new Pagination(
                  PaginationType.URL_TEMPLATE,
                  null,
                  null,
                  ".ready",
                  site.url("/list?page={value}"),
                  1,
                  1,
                  null)
              : new Pagination(
                  PaginationType.NEXT_LINK, ".next", "href", ".ready", null, null, null, null);
      var navigation = browserList(site.url("/list?page=1"), rule);
      assertBrowserLists(site, navigation, new HashMap<>(), "첫 번째", "두 번째");
    }
  }

  @Test
  void loadMoreWaitsForTheActualUpdateAndStopsAfterTwoEmptyActions() throws Exception {
    String first =
        """
        <main class=ready><article><h2>첫 글</h2><a href=/detail/1>본문</a></article></main>
        <button class=next>더 보기</button>
        <output class=done data-count="0"></output>
        <script>
          let run = 0;
          document.querySelector('button').onclick = async () => {
            const step = ++run;
            await fetch('/tick?step=' + step);
            if (step === 1) {
              document.querySelector('article').dataset.view = 'seen';
              document.querySelector('main').insertAdjacentHTML('beforeend', '<article><h2>둘째 글</h2><a href=/detail/2>본문</a></article>');
            }
            document.querySelector('.done').dataset.count = String(step);
          }
        </script>
        """;
    try (var site =
        new TestSite(
            first, Map.of("/tick?step=1", "ok", "/tick?step=2", "ok", "/tick?step=3", "ok"))) {
      var rule =
          new Pagination(PaginationType.LOAD_MORE, ".next", null, ".done", null, null, null, null);
      var stored = new HashMap<String, byte[]>();
      var lists =
          assertBrowserLists(site, browserList(site.url("/list"), rule), stored, "첫 글", "둘째 글");
      assertThat(lists.get(1).path("items").get(0).path("item_index").asInt()).isEqualTo(1);
      assertThat(site.requests)
          .filteredOn(url -> url.startsWith("/tick"))
          .containsExactly("/tick?step=1", "/tick?step=2", "/tick?step=3");
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void scrollsTheDocumentOrAConfiguredContainer(boolean container) throws Exception {
    String layout =
        """
        <main class=ready><section id=scroller style="%s">
          <article><h2>첫 글</h2></article><div style="height:1200px"></div></section>
          <output class=done data-count="0"></output></main>
        <script>
          let run = 0;
          const content = document.querySelector('#scroller');
          const scrollTarget = %s;
          scrollTarget.addEventListener('scroll', () => {
            if (++run === 1) {
              content.insertAdjacentHTML('beforeend','<article><h2>둘째 글</h2></article>');
            }
            content.insertAdjacentHTML('beforeend','<div style="height:600px"></div>');
            document.querySelector('.done').dataset.count = String(run);
          });
        </script>
        """
            .formatted(
                container ? "height:120px;overflow:auto" : "", container ? "content" : "window");
    try (var site = new TestSite(layout)) {
      var rule =
          new Pagination(
              PaginationType.SCROLL,
              null,
              null,
              ".done",
              null,
              null,
              null,
              container ? "#scroller" : null);
      var lists =
          assertBrowserLists(
              site, browserList(site.url("/list"), rule), new HashMap<>(), "첫 글", "둘째 글");
      assertThat(lists.get(1).path("items").get(0).path("item_index").asInt()).isEqualTo(1);
    }
  }

  @Test
  void reportsARepeatedClickPageInsteadOfPublishingTheRepeatedRows() throws Exception {
    String first =
        """
        <main class=ready><article><h2>첫 글</h2></article></main>
        <button class=next onclick="this.dataset.count=(Number(this.dataset.count??0)+1);
          document.querySelector('h2').textContent=this.dataset.count==='1'?'둘째 글':'첫 글'">다음</button>
        """;
    try (var site = new TestSite(first)) {
      var rule =
          new Pagination(PaginationType.CLICK_NEXT, ".next", null, ".next", null, null, null, null);
      var stored = new HashMap<String, byte[]>();
      var result =
          collection(site.access(), (key, type, bytes) -> stored.put(key, bytes))
              .execute(
                  listOnlyJob(browserList(site.url("/list"), rule)),
                  attempt(Duration.ofSeconds(10)));
      assertThat(result.stopReason()).isEqualTo("NAVIGATION_FAILED");
      assertThat(stored).isEmpty();
    }
  }

  @Test
  void missingScrollContainerReportsNavigationFailure() throws Exception {
    try (var site = new TestSite("<main class=ready><article><h2>첫 글</h2></article></main>")) {
      var rule =
          new Pagination(PaginationType.SCROLL, null, null, ".ready", null, null, null, ".missing");
      var result =
          collection(site.access(), (key, type, bytes) -> {})
              .execute(
                  listOnlyJob(browserList(site.url("/list"), rule)),
                  attempt(Duration.ofSeconds(10)));
      assertThat(result.stopReason()).isEqualTo("NAVIGATION_FAILED");
      assertThat(result.snapshotRef()).isNull();
    }
  }

  @Test
  void stopsAWaitingBrowserActionAtTheRunDeadlineAndKeepsTheFirstList() throws Exception {
    try (var site =
        new TestSite(
            """
            <main class=ready><article><h2>첫 글</h2></article></main>
            <button class=next onclick="this.disabled=true">더 보기</button>
            """)) {
      var rule =
          new Pagination(PaginationType.LOAD_MORE, ".next", null, ".never", null, null, null, null);
      var stored = new HashMap<String, byte[]>();
      var result =
          collection(site.access(), (key, type, bytes) -> stored.put(key, bytes))
              .execute(
                  listOnlyJob(browserList(site.url("/list"), rule)),
                  attempt(Duration.ofSeconds(3)));
      assertThat(result.stopReason()).isEqualTo("TIME_LIMIT");
      assertThat(result.status()).isEqualTo("SUCCEEDED");
      assertThat(read(stored.get(result.snapshotRef().key())).path("list_snapshots")).hasSize(1);
    }
  }

  @Test
  void missingReadinessSelectorIsNotCountedAsAnUnchangedList() throws Exception {
    try (var site =
        new TestSite(
            """
            <main class=ready><article><h2>첫 글</h2></article></main>
            <button class=next onclick="this.dataset.clicked=true">더 보기</button>
            """)) {
      var rule =
          new Pagination(PaginationType.LOAD_MORE, ".next", null, ".never", null, null, null, null);
      var stored = new HashMap<String, byte[]>();
      var result =
          collection(site.access(), (key, type, bytes) -> stored.put(key, bytes))
              .execute(
                  listOnlyJob(browserList(site.url("/list"), rule)),
                  attempt(Duration.ofSeconds(40)));
      assertThat(result.stopReason()).isEqualTo("NAVIGATION_FAILED");
      assertThat(result.error().details().get("reason")).isEqualTo("BROWSER_TIMEOUT");
      assertThat(stored).isEmpty();
    }
  }

  @Test
  void blocksASameOriginDataRequestTriggeredByTheListAction() throws Exception {
    try (var site =
        new TestSite(
            """
            <main class=ready><article><h2>첫 글</h2></article></main>
            <button class=next onclick="fetch('/blocked').catch(() => {
              document.querySelector('h2').textContent='차단 뒤 대체값'
            })">더 보기</button>
            """)) {
      var rule =
          new Pagination(PaginationType.LOAD_MORE, ".next", null, ".ready", null, null, null, null);
      var stored = new HashMap<String, byte[]>();
      var result =
          collection(site.access(), (key, type, bytes) -> stored.put(key, bytes))
              .execute(
                  listOnlyJob(browserList(site.url("/list"), rule)),
                  attempt(Duration.ofSeconds(10)));
      assertThat(site.requests).contains("/blocked");
      assertThat(result.stopReason()).isEqualTo("ACCESS_BLOCKED");
      assertThat(stored).isEmpty();
    }
  }

  private static String dynamicScreenshot(String title, String next) {
    String link = next == null ? "" : "<a class=next href='" + next + "'>다음</a>";
    return """
    <main></main>
    <script>
      setTimeout(() => {
        document.querySelector('main').innerHTML = %s;
        document.querySelector('main').className = 'ready';
      }, 25);
    </script>
    """
        .formatted(
            JsonMapper.shared()
                .writeValueAsString("<article><h2>" + title + "</h2></article>" + link));
  }

  private static Navigation browserList(String address, Pagination rule) {
    return new Navigation(
        address, List.of(), new ListAccess(AccessMethod.BROWSER, ".ready"), "article", rule);
  }

  @Test
  void cumulativeSegmentsSpanDomRepaintsAndKeepTheFinalShortSegment() throws Exception {
    String list =
        """
        <main class=ready>
          <article><h2>A</h2><a href=/x/a>A</a></article>
          <article><h2>B</h2><a href=/x/b>B</a></article>
          <article><h2>N1</h2><a href=/x/1>1</a></article>
        </main>
        <button class=next>더 보기</button>
        <script>
          let n = 0;
          document.querySelector('.next').onclick = event => {
            document.querySelector('article').dataset.view = 'seen';
            for (let i = 0; i < 2; i++) {
              const id = ++n;
              document.querySelector('main').insertAdjacentHTML(
                'beforeend', '<article><h2>새 값 ' + id + '</h2><a href=/y/' + id + '>Link</a></article>');
            }
            if (n === 4) event.target.disabled = true;
          };
        </script>
        """;
    try (var site = new TestSite(list)) {
      var rule =
          new Pagination(PaginationType.LOAD_MORE, ".next", null, ".ready", null, null, null, null);
      var full = listOnlyJob(browserList(site.url("/list"), rule));
      var request = full.payload();
      var source = request.projects().getFirst();
      var incremental =
          new CollectionProject(
              source.projectId(),
              source.projectSourceId(),
              source.schemaVersion(),
              source.connectorRevision(),
              "INCREMENTAL",
              source.schema(),
              source.fieldConnectors(),
              List.of(
                  new LatestRecord(Map.of(TITLE, "A")),
                  new LatestRecord(Map.of(TITLE, "B")),
                  new LatestRecord(Map.of(TITLE, "C"))));
      var job =
          new Job<>(
              full.id(),
              full.expiresAt(),
              full.workBudget(),
              new CollectionExecute(
                  request.sourceId(),
                  "SCHEDULED",
                  request.maxDurationSeconds(),
                  request.navigationRevision(),
                  request.navigation(),
                  List.of(incremental)));
      var stored = new HashMap<String, byte[]>();
      var result =
          collection(site.access(), (key, type, bytes) -> stored.put(key, bytes))
              .execute(job, attempt(Duration.ofSeconds(15)));

      assertThat(result.stopReason()).withFailMessage("%s", result.error()).isEqualTo("NO_CHANGE");
      assertThat(result.projectResults().getFirst().normalExtractedRecordCount()).isEqualTo(7L);
      var pages = read(stored.get(result.snapshotRef().key())).path("list_snapshots");
      assertThat(pages.get(2).path("items").get(0).path("item_index").asInt()).isEqualTo(5);
      assertThat(pages.get(2).path("items").get(1).path("item_index").asInt()).isEqualTo(6);
    }
  }

  private static Job<CollectionExecute> listOnlyJob(Navigation nav) {
    var original = job(nav.listUrl(), AccessMethod.BROWSER, OpenType.LINK);
    var first = original.payload().projects().getFirst();
    var project =
        new CollectionProject(
            first.projectId(),
            first.projectSourceId(),
            first.schemaVersion(),
            first.connectorRevision(),
            "FULL",
            new Schema(List.of(first.schema().fields().getFirst())),
            List.of(first.fieldConnectors().getFirst()),
            List.of());
    var request =
        new CollectionExecute(
            original.payload().sourceId(), "INITIAL", 20, 4L, nav, List.of(project));
    return new Job<>(original.id(), original.expiresAt(), original.workBudget(), request);
  }

  private static JsonNode assertBrowserLists(
      TestSite site, Navigation nav, Map<String, byte[]> stored, String... expected)
      throws Exception {
    var result =
        collection(site.access(), (key, type, bytes) -> stored.put(key, bytes))
            .execute(listOnlyJob(nav), attempt(Duration.ofSeconds(15)));
    assertThat(result.status()).isEqualTo("SUCCEEDED");
    assertThat(result.stopReason()).isEqualTo("END_OF_LIST");
    assertThat(result.listItemCount()).isEqualTo(expected.length);
    var lists = read(stored.get(result.snapshotRef().key())).path("list_snapshots");
    var titles = new ArrayList<String>();
    for (var list : lists) {
      var document = HtmlScope.parse(html(stored.get(key(list))));
      for (var item : list.path("items")) {
        assertThat(item.path("sequence_index").asInt()).isEqualTo(titles.size());
        titles.add(
            text(
                document.selectItem(
                    list.path("item_selector").asString(), item.path("item_index").asInt()),
                "h2"));
      }
    }
    assertThat(titles).containsExactly(expected);
    return lists;
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
    return job(url, method, openType, Target.CURRENT_TAB);
  }

  private static Job<CollectionExecute> job(
      String url, AccessMethod method, OpenType openType, Target target) {
    var navigation =
        new Navigation(
            url,
            List.of(),
            new ListAccess(method, null),
            "article",
            new Pagination(PaginationType.NONE, null, null, null, null, null, null, null));
    var returnType =
        switch (target) {
          case CURRENT_TAB -> ReturnType.HISTORY_BACK;
          case NEW_TAB -> ReturnType.CLOSE_TAB;
          case MODAL -> ReturnType.CLOSE;
        };
    var detail =
        new DetailNavigation(
            new DetailAccess(DetailAccessMethod.BROWSER, ".body"),
            new DetailOpen(
                openType,
                openType == OpenType.LINK ? "a" : "button",
                openType == OpenType.LINK ? "href" : null,
                target),
            new DetailReturn(returnType, ".list", target == Target.MODAL ? ".close" : null));
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

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void newTabDetailsReturnToTheSameRowAndPersistOnlyVerifiedDirectLinks(boolean click)
      throws Exception {
    var list =
        LIST.replace("location.href='/detail/1'", "window.open('/detail/1')")
            .replace("location.href='/detail/2'", "window.open('/detail/2')");
    try (var site = new TestSite(list)) {
      var stored = new HashMap<String, byte[]>();
      var result =
          collection(site.access(), (key, mime, bytes) -> stored.put(key, bytes))
              .execute(
                  job(
                      site.url("/list"),
                      AccessMethod.BROWSER,
                      click ? OpenType.CLICK : OpenType.LINK,
                      Target.NEW_TAB),
                  attempt(Duration.ofSeconds(12)));
      assertThat(result.stopReason())
          .withFailMessage("%s", result.error())
          .isEqualTo("END_OF_LIST");
      assertThat(site.detailRequests.get()).isEqualTo(2);
      var items =
          read(stored.get(result.snapshotRef().key())).path("list_snapshots").get(0).path("items");
      assertThat(items).hasSize(2);
      for (var item : items) {
        var index = item.path("item_index").asInt();
        var detail = HtmlScope.parse(html(stored.get(key(item.path("details").get(0)))));
        assertThat(text(detail, ".body")).isEqualTo("본문 " + (index + 1));
        var sourceUrl = item.path("source_urls").get(0).path("source_url");
        if (click) {
          assertThat(sourceUrl.isNull()).isTrue();
        } else {
          assertThat(sourceUrl.asString()).isEqualTo(site.url("/detail/" + (index + 1)));
        }
      }
    }
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void modalDetailsUseOnlyTheModalFragmentAndDoNotPersistADirectUrl(boolean click)
      throws Exception {
    try (var site = new TestSite(modalList("this.closest('dialog').close()", false))) {
      var stored = new HashMap<String, byte[]>();
      var result =
          collection(site.access(), (key, mime, bytes) -> stored.put(key, bytes))
              .execute(
                  job(
                      site.url("/list"),
                      AccessMethod.BROWSER,
                      click ? OpenType.CLICK : OpenType.LINK,
                      Target.MODAL),
                  attempt(Duration.ofSeconds(12)));
      assertThat(result.stopReason())
          .withFailMessage("%s", result.error())
          .isEqualTo("END_OF_LIST");
      assertThat(site.detailRequests.get()).isZero();
      var items =
          read(stored.get(result.snapshotRef().key())).path("list_snapshots").get(0).path("items");
      assertThat(items).hasSize(2);
      for (var item : items) {
        int index = item.path("item_index").asInt();
        var raw = html(stored.get(key(item.path("details").get(0))));
        assertThat(raw).doesNotContain("outside the dialog");
        var detail = HtmlScope.parse(raw);
        assertThat(text(detail, ".body")).isEqualTo("본문 " + (index + 1));
        assertThat(item.path("source_urls").get(0).path("source_url").isNull()).isTrue();
      }
    }
  }

  @Test
  void alreadyOpenModalIsRejectedInsteadOfSavingStaleContent() throws Exception {
    try (var site = new TestSite(modalList("this.closest('dialog').close()", true))) {
      var stored = new HashMap<String, byte[]>();
      var result =
          collection(site.access(), (key, mime, bytes) -> stored.put(key, bytes))
              .execute(
                  job(site.url("/list"), AccessMethod.BROWSER, OpenType.CLICK, Target.MODAL),
                  attempt(Duration.ofSeconds(12)));
      assertThat(result.stopReason()).isEqualTo("NAVIGATION_FAILED");
      assertThat(result.snapshotRef()).isNull();
      assertThat(stored).isEmpty();
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "window.open('/detail/1'); window.open('/detail/2')",
        "window.open('/detail/1'); history.replaceState(null, '', '/other')"
      })
  void ambiguousNewTabActionDoesNotPublishASnapshot(String action) throws Exception {
    try (var site = new TestSite(LIST.replace("location.href='/detail/1'", action))) {
      var stored = new HashMap<String, byte[]>();
      var result =
          collection(site.access(), (key, mime, bytes) -> stored.put(key, bytes))
              .execute(
                  job(site.url("/list"), AccessMethod.BROWSER, OpenType.CLICK, Target.NEW_TAB),
                  attempt(Duration.ofSeconds(12)));
      assertThat(result.stopReason()).isEqualTo("NAVIGATION_FAILED");
      assertThat(result.snapshotRef()).isNull();
      assertThat(stored).isEmpty();
    }
  }

  @Test
  void closingTheModalMustNotNavigateTheOriginalTab() throws Exception {
    try (var site =
        new TestSite(
            modalList(
                "this.closest('dialog').close(); history.replaceState(null, '', '/other')",
                false))) {
      var stored = new HashMap<String, byte[]>();
      var result =
          collection(site.access(), (key, mime, bytes) -> stored.put(key, bytes))
              .execute(
                  job(site.url("/list"), AccessMethod.BROWSER, OpenType.CLICK, Target.MODAL),
                  attempt(Duration.ofSeconds(12)));
      assertThat(result.stopReason()).isEqualTo("NAVIGATION_FAILED");
      assertThat(result.snapshotRef()).isNull();
      assertThat(stored).isEmpty();
    }
  }

  private static String modalList(String closeAction, boolean alreadyOpen) {
    return """
    <main class=list>
      <article><h2>첫 글</h2><button onclick="show(1)">Open</button>
        <a href="#detail" onclick="show(1);return false">Details</a></article>
      <article><h2>둘째 글</h2><button onclick="show(2)">Open</button>
        <a href="#detail" onclick="show(2);return false">Details</a></article>
      <p>outside the dialog</p>
    </main>
    <dialog><div class=body></div><span class=author>작성자</span>
      <button class=close onclick="%s">Close</button></dialog>
    <script>
      const dialog = document.querySelector('dialog');
      function show(i) {
        dialog.querySelector('.body').textContent = '본문 ' + i;
        dialog.showModal();
      }
      %s
    </script>
    """
        .formatted(closeAction, alreadyOpen ? "show(9);" : "");
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
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final AtomicInteger detailRequests = new AtomicInteger();
    private volatile boolean reorderAfterDetail;
    private volatile boolean detailReplacesUrl;
    private volatile boolean cosmeticChangeAfterDetail;
    private volatile boolean linkChangesAfterDetail;
    private volatile boolean robotsDenied;

    TestSite(String list) throws IOException {
      this(list, Map.of());
    }

    TestSite(String list, Map<String, String> pages) throws IOException {
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
              String requested = exchange.getRequestURI().toString();
              requests.add(requested);
              if (requested.equals("/blocked")) {
                exchange.sendResponseHeaders(403, -1);
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
                body = pages.getOrDefault(requested, listResponse(list));
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
