package com.weavo.crawler.collection.application;

import com.weavo.crawler.collection.application.CollectionJobHandlers.IssueReporter;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionCompleted;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionExecute;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionProject;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionProjectResult;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.MessageError;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.ObjectReference;
import com.weavo.extraction.FieldValueProcessor;
import com.weavo.extraction.FieldValueProcessor.ParserUnavailable;
import com.weavo.extraction.HtmlScope;
import com.weavo.siteaccess.BrowserList;
import com.weavo.siteaccess.BrowserSession;
import com.weavo.siteaccess.BrowserTab;
import com.weavo.siteaccess.ListNavigation;
import com.weavo.siteaccess.SiteAccess;
import com.weavo.siteaccess.SiteAccess.PageSnapshot;
import com.weavo.siteaccess.SiteAccessException;
import com.weavo.siteaccess.SiteAccessException.Kind;
import com.weavo.siteaccess.SiteSession;
import com.weavo.siteaccess.WorkBudget;
import com.weavo.siteaccess.rule.FieldConnector.DetailNavigation;
import com.weavo.siteaccess.rule.FieldConnector.Target;
import com.weavo.siteaccess.rule.Navigation;
import com.weavo.siteaccess.rule.Navigation.AccessMethod;
import com.weavo.siteaccess.rule.Navigation.PaginationType;
import com.weavo.worker.application.AttemptContext;
import com.weavo.worker.application.JobProcessor.Job;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Function;
import java.util.stream.IntStream;

/** Collects one configured list and its current-tab details for one collection attempt. */
public final class FixedCollection implements CollectionJobHandlers.Handler {
  private final SiteAccess siteAccess;
  private final Function<WorkBudget, FieldValueProcessor> fields;
  private final String bucket;
  private final SnapshotUpload store;

  @FunctionalInterface
  public interface SnapshotUpload {
    void put(String key, String contentType, byte[] bytes);
  }

  public FixedCollection(
      SiteAccess siteAccess, FieldValueProcessor fields, String bucket, SnapshotUpload upload) {
    this(siteAccess, budget -> fields, bucket, upload);
  }

  public FixedCollection(
      SiteAccess siteAccess,
      Function<WorkBudget, FieldValueProcessor> fields,
      String bucket,
      SnapshotUpload upload) {
    this.siteAccess = siteAccess;
    this.fields = fields;
    this.bucket = bucket;
    this.store = upload;
  }

  @Override
  public CollectionCompleted execute(Job<CollectionExecute> job, AttemptContext attempt)
      throws InterruptedException {
    return execute(job, attempt, (id, fields, error) -> {});
  }

  @Override
  public CollectionCompleted execute(
      Job<CollectionExecute> job, AttemptContext attempt, IssueReporter reporter)
      throws InterruptedException {
    var request = job.payload();
    var budget =
        new WorkBudget(
            attempt.workDeadline(), attempt::isCancellationRequested, attempt::requireWorkAllowed);
    var rules = new FixedCollectionFields(request.projects(), fields.apply(budget));
    try (var snapshot = new CollectionSnapshot(bucket, job.id(), attempt)) {
      var state = new CaptureState(request, reporter);
      String reason = "END_OF_LIST";
      try {
        collect(request, budget, rules, snapshot, state);
        if (state.stoppedEarly) {
          reason = state.allFailed() ? state.failureStopReason : "NO_CHANGE";
        }
      } catch (SiteAccessException failure) {
        if (failure.kind() == Kind.CANCELLED) {
          throw new CancellationException("Fixed collection no longer owns this attempt");
        }
        if (failure.kind() != Kind.WORK_LIMIT) {
          var error = accessError(failure.kind(), state);
          state.reporter.report(null, List.of(), error);
          return result(
              request, attempt, stopReason(failure.kind(), state), state, false, null, error);
        }
        reason = "TIME_LIMIT";
      } catch (ParserUnavailable failure) {
        return result(
            request,
            attempt,
            "TECHNICAL_FAILURE",
            state,
            false,
            null,
            error("TECHNICAL_FAILURE", "PARSER_CALL", "격리 파서의 응답을 확인하지 못했습니다."));
      }
      if (state.listCount != null && state.listCount == 0) {
        var error =
            new MessageError(
                "EMPTY_LIST",
                "LIST_EXTRACTION",
                "반복 목록 항목을 찾지 못했습니다.",
                Map.of("observed_count", 0),
                false,
                null);
        state.reporter.report(null, List.of(), error);
        return result(request, attempt, "EMPTY_LIST", state, false, null, error);
      }
      if (snapshot.itemCount() == 0) {
        return result(
            request,
            attempt,
            reason,
            state,
            false,
            null,
            error("TECHNICAL_FAILURE", state.stage, "수집 기한 안에 완료한 항목이 없습니다."));
      }

      try {
        var ref = state.hasCandidates() ? snapshot.publish(request, store) : null;
        return result(request, attempt, reason, state, true, ref, null);
      } catch (CollectionSnapshot.UploadFailed failure) {
        attempt.requireOwnership();
        return result(
            request,
            attempt,
            "TECHNICAL_FAILURE",
            state,
            true,
            null,
            error("SNAPSHOT_WRITE_FAILED", "SNAPSHOT_WRITE", "수집 스냅샷을 저장하지 못했습니다."));
      }
    }
  }

  private void collect(
      CollectionExecute request,
      WorkBudget budget,
      FixedCollectionFields rules,
      CollectionSnapshot snapshot,
      CaptureState state)
      throws InterruptedException {
    Navigation navigation = request.navigation();
    SiteSession sites = siteAccess.open(budget);
    if (navigation.listAccess().method() == AccessMethod.HTTP) {
      var first = httpPage(sites, navigation, navigation.listUrl(), state);
      state.listCount = (long) first.items().size();
      if (first.items().isEmpty()) {
        return;
      }
      if (!rules.hasDetails(state.progress::participates)) {
        collectHttpPages(request, budget, rules, snapshot, state, sites, first, null);
        return;
      }
      try (BrowserSession browser = sites.openBrowser();
          BrowserTab tab = browser.newTab()) {
        collectHttpPages(request, budget, rules, snapshot, state, sites, first, tab);
      }
      return;
    }

    try (BrowserSession browser = sites.openBrowser();
        BrowserTab tab = browser.newTab()) {
      collectBrowserPages(request, budget, rules, snapshot, state, tab);
    }
  }

  private void collectBrowserPages(
      CollectionExecute request,
      WorkBudget budget,
      FixedCollectionFields rules,
      CollectionSnapshot snapshot,
      CaptureState state,
      BrowserTab tab)
      throws InterruptedException {
    Navigation nav = request.navigation();
    String itemSelector = nav.itemSelector();
    String listWait = nav.listAccess().waitSelector();
    var pagination = nav.pagination();
    var movement = new ListNavigation(pagination);
    boolean cumulative =
        pagination.type() == PaginationType.LOAD_MORE || pagination.type() == PaginationType.SCROLL;
    PageSnapshot page =
        tab.navigate(nav.listUrl(), nav.initialActions().isEmpty() ? listWait : null);
    if (!nav.initialActions().isEmpty()) {
      state.stage = "LIST_NAVIGATION";
      for (var action : nav.initialActions()) {
        page = tab.clickList(action.selector(), action.waitSelector(), null);
      }
      if (listWait != null) {
        page = tab.awaitList(listWait);
      }
    }

    int unchanged = 0;
    state.listCount = 0L;
    while (page != null) {
      state.stage = "LIST_EXTRACTION";
      var browserList = BrowserList.capture(tab, page, itemSelector);
      var items = selectItems(browserList.document(), itemSelector);
      BrowserList.verifyItems(items, browserList.markup());
      boolean first = state.listCount == 0;
      if (first && items.isEmpty()) {
        return;
      }
      state.stage = "LIST_NAVIGATION";
      if (!cumulative && items.isEmpty()) {
        if (pagination.type() == PaginationType.CLICK_NEXT) {
          throw new SiteAccessException(
              Kind.BROWSER_FAILED, "The click did not reach another list");
        }
        return;
      }
      List<Integer> indices;
      if (cumulative) {
        indices = movement.observeCumulativeList(items);
        unchanged = indices.isEmpty() ? unchanged + 1 : 0;
        if (unchanged == 2) {
          break;
        }
      } else {
        movement.observePage(items);
        indices = itemIndices(items);
      }
      state.listCount += indices.size();
      if (!indices.isEmpty()) {
        captureItems(
            request,
            rules,
            budget,
            snapshot,
            state,
            page,
            items,
            indices,
            tab,
            browserList,
            cumulative);
        if (state.stoppedEarly || (!cumulative && state.endSegment())) {
          return;
        }
      }
      state.stage = "LIST_NAVIGATION";
      page = movement.moveBrowser(tab, nav, browserList.document());
    }
    if (state.segmentRows > 0) {
      state.endSegment();
    }
  }

  private void collectHttpPages(
      CollectionExecute request,
      WorkBudget budget,
      FixedCollectionFields rules,
      CollectionSnapshot snapshot,
      CaptureState state,
      SiteSession sites,
      HttpListPage first,
      BrowserTab tab)
      throws InterruptedException {
    Navigation navigation = request.navigation();
    var movement = new ListNavigation(navigation.pagination());
    HttpListPage current = first;
    while (!current.items().isEmpty()) {
      state.stage = "LIST_NAVIGATION";
      movement.observePage(current.items());
      var browserList =
          tab != null && rules.hasDetails(state.progress::participates)
              ? browserListForHttp(tab, current.page(), navigation)
              : null;
      captureItems(
          request,
          rules,
          budget,
          snapshot,
          state,
          current.page(),
          current.items(),
          itemIndices(current.items()),
          tab,
          browserList,
          false);
      if (state.stoppedEarly || state.endSegment()) {
        return;
      }
      state.stage = "LIST_NAVIGATION";
      String next = movement.nextUrl(current.document());
      if (next == null) {
        return;
      }
      current = httpPage(sites, navigation, next, state);
      state.listCount += current.items().size();
    }
  }

  private static HttpListPage httpPage(
      SiteSession sites, Navigation navigation, String url, CaptureState state) {
    PageSnapshot page = sites.fetchHtml(url);
    state.stage = "LIST_EXTRACTION";
    var document = HtmlScope.parse(page.html(), page.url().toString());
    return new HttpListPage(page, document, selectItems(document, navigation.itemSelector()));
  }

  private static BrowserList browserListForHttp(
      BrowserTab tab, PageSnapshot list, Navigation navigation) {
    var rendered = tab.navigate(list.url().toString(), navigation.listAccess().waitSelector());
    if (!list.url().equals(rendered.url())) {
      throw new SiteAccessException(Kind.BROWSER_FAILED, "Browser and HTTP list addresses differ");
    }
    return BrowserList.capture(tab, rendered, navigation.itemSelector());
  }

  private record HttpListPage(PageSnapshot page, HtmlScope document, List<HtmlScope> items) {}

  private void captureItems(
      CollectionExecute request,
      FixedCollectionFields rules,
      WorkBudget budget,
      CollectionSnapshot snapshot,
      CaptureState state,
      PageSnapshot list,
      List<HtmlScope> items,
      List<Integer> indices,
      BrowserTab tab,
      BrowserList initialBrowserList,
      boolean cumulative)
      throws InterruptedException {
    String selector = request.navigation().itemSelector();
    BrowserList current = initialBrowserList;
    var originalRows = List.<HtmlScope>of();
    if (initialBrowserList != null) {
      originalRows = initialBrowserList.document().select(selector);
      if (request.navigation().listAccess().method() == AccessMethod.HTTP) {
        BrowserList.verifyItems(items, initialBrowserList.markup());
        BrowserList.verifyItems(originalRows, initialBrowserList.markup());
      }
    }
    snapshot.captureList(list, request.navigation());
    for (int index : indices) {
      state.stage = "LIST_EXTRACTION";
      budget.check();
      Map<String, Map<String, Object>> values = new LinkedHashMap<>();
      for (var project : request.projects()) {
        String id = project.projectSourceId();
        if (state.progress.participates(id)) {
          values.put(id, new LinkedHashMap<>());
        }
      }
      var participants = List.copyOf(values.keySet());
      reportFieldFailures(
          state, snapshot, rules.extract(rules.listFields(), items.get(index), budget, values));
      var details = new ArrayList<CollectionSnapshot.Detail>();
      Map<String, List<String>> directLinks = new LinkedHashMap<>();
      state.stage = "DETAIL_NAVIGATION";
      for (var visit : rules.detailVisits(items.get(index), values.keySet())) {
        var detailFields =
            visit.fields().stream()
                .filter(field -> values.containsKey(field.projectSourceId()))
                .toList();
        if (detailFields.isEmpty()) {
          continue;
        }
        var rule = visit.rule();
        state.stage = "DETAIL_NAVIGATION";
        var originalRow = originalRows.get(index);
        String originalMarkup = initialBrowserList.markup().get(index);
        var consumers = FixedCollectionFields.consumers(detailFields);
        BrowserTab.DetailSnapshot detail;
        BrowserTab.Item browserItem = null;
        try {
          browserItem = current.reselect(selector, index, originalRow, originalMarkup, rule);
          detail = tab.openDetail(browserItem, rule);
        } catch (SiteAccessException failure) {
          if (failure.kind() == Kind.CANCELLED || failure.kind() == Kind.WORK_LIMIT) {
            throw failure;
          }
          state.failureStopReason = stopReason(failure.kind(), state);
          var error = accessError(failure.kind(), state);
          for (var consumer : consumers) {
            state.fail(consumer.projectSourceId(), consumer.fieldIds(), error);
            values.remove(consumer.projectSourceId());
          }
          if (browserItem != null
              && failure.kind() != Kind.DETAIL_CONTROL_UNAVAILABLE
              && !state.allFailed()) {
            state.stage = "DETAIL_RETURN";
            current = resumeList(tab, list, selector, index, originalRow, rule);
          }
          continue;
        }
        var page = detail.page();
        reportFieldFailures(
            state,
            snapshot,
            rules.extract(detailFields, HtmlScope.parse(page.html()), budget, values));
        details.add(snapshot.captureDetail(page, consumers));
        if (detail.directUrl() != null && !detail.directUrl().equals(list.url())) {
          for (var consumer : consumers) {
            directLinks
                .computeIfAbsent(consumer.projectSourceId(), ignored -> new ArrayList<>())
                .add(detail.directUrl().toString());
          }
        }

        state.stage = "DETAIL_RETURN";
        var returned = tab.returnToList(rule);
        if (!returned.url().equals(list.url())) {
          throw new SiteAccessException(Kind.BROWSER_FAILED, "Detail did not return to the list");
        }
        current = BrowserList.capture(tab, returned, selector);
        current.reselect(selector, index, originalRow, originalMarkup, rule);
      }
      var urls =
          participants.stream()
              .map(id -> new CollectionSnapshot.SourceUrl(id, uniqueLink(directLinks.get(id))))
              .toList();
      var failedParticipants = participants.stream().filter(id -> !values.containsKey(id)).toList();
      snapshot.addItem(index, urls, details, failedParticipants);
      state.progress.observe(values);
      if (state.allFailed()) {
        state.stoppedEarly = true;
        return;
      }
      if (cumulative && ++state.segmentRows == 5 && state.endSegment()) {
        return;
      }
    }
  }

  private static List<Integer> itemIndices(List<HtmlScope> items) {
    return IntStream.range(0, items.size()).boxed().toList();
  }

  private static List<HtmlScope> selectItems(HtmlScope document, String selector) {
    try {
      return document.select(selector);
    } catch (IllegalArgumentException | IllegalStateException failure) {
      throw new SiteAccessException(Kind.BROWSER_FAILED, "Invalid list item selector");
    }
  }

  private static String uniqueLink(List<String> urls) {
    if (urls == null || urls.isEmpty()) {
      return null;
    }
    String first = urls.getFirst();
    return urls.stream().allMatch(first::equals) ? first : null;
  }

  private static String stopReason(Kind kind, CaptureState state) {
    return switch (kind) {
      case ACCESS_BLOCKED, ROBOTS_DENIED, INVALID_URL -> "ACCESS_BLOCKED";
      case DETAIL_CONTROL_UNAVAILABLE -> "NAVIGATION_FAILED";
      case BROWSER_FAILED, BROWSER_TIMEOUT, REDIRECT_LIMIT ->
          state.stage.equals("LIST_ACCESS") ? "TECHNICAL_FAILURE" : "NAVIGATION_FAILED";
      default -> "TECHNICAL_FAILURE";
    };
  }

  private static MessageError accessError(Kind kind, CaptureState state) {
    String code =
        switch (kind) {
          case ACCESS_BLOCKED, ROBOTS_DENIED, INVALID_URL -> "ACCESS_BLOCKED";
          case SITE_UNAVAILABLE, HTTP_TIMEOUT, HTTP_STATUS -> "SITE_UNAVAILABLE";
          case DETAIL_CONTROL_UNAVAILABLE -> "NAVIGATION_FAILED";
          case BROWSER_FAILED, BROWSER_TIMEOUT, REDIRECT_LIMIT ->
              state.stage.equals("LIST_ACCESS") ? "BROWSER_FAILED" : "NAVIGATION_FAILED";
          default -> "TECHNICAL_FAILURE";
        };
    return new MessageError(
        code,
        state.stage,
        "수집 화면을 확보하거나 다시 확인하지 못했습니다.",
        Map.of("reason", kind.name()),
        false,
        null);
  }

  private void reportFieldFailures(
      CaptureState state,
      CollectionSnapshot snapshot,
      List<FixedCollectionFields.FieldFailure> failures)
      throws InterruptedException {
    for (var failure : failures) {
      String id = failure.projectSourceId();
      var error = failure.error();
      if (!failure.rawValues().isEmpty()) {
        var evidence =
            snapshot.storeFailure(
                state.projects.get(id), failure.fieldId(), failure.rawValues(), store);
        error =
            new MessageError(
                error.code(),
                error.stage(),
                error.message(),
                error.details(),
                error.retryable(),
                List.of(evidence));
      }
      state.fail(id, List.of(failure.fieldId()), error);
    }
  }

  private static BrowserList resumeList(
      BrowserTab tab,
      PageSnapshot list,
      String selector,
      int index,
      HtmlScope original,
      DetailNavigation rule) {
    // A failed modal may still cover the list. Without a verified return this is a source failure.
    if (rule.open().target() == Target.MODAL) {
      throw new SiteAccessException(Kind.BROWSER_FAILED, "Could not confirm the modal return");
    }
    var observed = tab.awaitList(null);
    if (!observed.url().equals(list.url()) && rule.open().target() == Target.CURRENT_TAB) {
      observed = tab.goBack(rule.returnRule().waitSelector());
    }
    if (!observed.url().equals(list.url())) {
      throw new SiteAccessException(Kind.BROWSER_FAILED, "Could not resume the verified list");
    }
    var resumed = BrowserList.capture(tab, observed, selector);
    var rows = resumed.document().select(selector);
    if (index >= rows.size()
        || !original.markupFingerprint().equals(rows.get(index).markupFingerprint())) {
      throw new SiteAccessException(
          Kind.BROWSER_FAILED, "Could not reselect the verified list item");
    }
    BrowserList.verifyItems(rows, resumed.markup());
    return resumed;
  }

  private static CollectionCompleted result(
      CollectionExecute request,
      AttemptContext attempt,
      String stopReason,
      CaptureState state,
      boolean measured,
      ObjectReference snapshot,
      MessageError error) {
    String status =
        error != null || (state.allFailed() && snapshot == null)
            ? "FAILED"
            : state.failures.isEmpty() ? "SUCCEEDED" : "PARTIAL";
    var projects =
        request.projects().stream()
            .map(project -> {
              String id = project.projectSourceId();
              var projectError = state.failures.getOrDefault(id, error);
              boolean canIngest = error == null && snapshot != null && state.canIngest(id);
              String projectStatus =
                  projectError == null ? "SUCCEEDED" : canIngest ? "PARTIAL" : "FAILED";
              return new CollectionProjectResult(
                  project.projectId(),
                  project.projectSourceId(),
                  project.schemaVersion(),
                  project.connectorRevision(),
                  projectStatus,
                  canIngest,
                  measured ? state.progress.count(id) : null,
                  projectError);
            })
            .toList();
    return new CollectionCompleted(
        attempt.id().toString(),
        request.sourceId(),
        request.navigationRevision(),
        status,
        stopReason,
        attempt.startedAt().toString(),
        attempt.now().toString(),
        state.listCount,
        snapshot,
        projects,
        error == null && "FAILED".equals(status)
            ? state.failures.values().iterator().next()
            : error);
  }

  private static MessageError error(String code, String stage, String message) {
    return new MessageError(code, stage, message, Map.of(), false, null);
  }

  private static final class CaptureState {
    final CollectionProgress progress;
    final Map<String, CollectionProject> projects = new LinkedHashMap<>();
    final Map<String, MessageError> failures = new LinkedHashMap<>();
    final IssueReporter reporter;
    final boolean acceptsPartialRecords;
    String failureStopReason = "TECHNICAL_FAILURE";
    String stage = "LIST_ACCESS";
    Long listCount;
    int segmentRows;
    boolean stoppedEarly;

    CaptureState(CollectionExecute request, IssueReporter reporter) {
      this.progress = new CollectionProgress(request.projects());
      this.reporter = reporter;
      this.acceptsPartialRecords =
          Set.of("SCHEDULED", "MANUAL").contains(request.executionReason());
      request.projects().forEach(project -> projects.put(project.projectSourceId(), project));
    }

    void fail(String id, List<String> fields, MessageError error) throws InterruptedException {
      if (failures.containsKey(id)) {
        return;
      }
      var fieldIds = List.copyOf(fields);
      var details = new LinkedHashMap<>(error.details());
      details.put("field_ids", fieldIds);
      var scopedError =
          new MessageError(
              error.code(),
              error.stage(),
              error.message(),
              details,
              error.retryable(),
              error.failureEvidence());
      failures.put(id, scopedError);
      progress.stop(id);
      reporter.report(id, fieldIds, scopedError);
    }

    boolean hasCandidates() {
      return projects.keySet().stream().anyMatch(this::canIngest);
    }

    boolean canIngest(String id) {
      return progress.hasCandidates(id) && (acceptsPartialRecords || !failures.containsKey(id));
    }

    boolean allFailed() {
      return failures.size() == projects.size();
    }

    boolean endSegment() {
      segmentRows = 0;
      stoppedEarly = progress.endSegment();
      return stoppedEarly;
    }
  }
}
