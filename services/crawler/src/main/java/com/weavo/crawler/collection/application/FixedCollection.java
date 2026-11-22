package com.weavo.crawler.collection.application;

import com.weavo.crawler.collection.domain.rule.FieldConnector.DetailNavigation;
import com.weavo.crawler.collection.domain.rule.FieldConnector.OpenType;
import com.weavo.crawler.collection.domain.rule.FieldConnector.Target;
import com.weavo.crawler.collection.domain.rule.Navigation;
import com.weavo.crawler.collection.domain.rule.Navigation.AccessMethod;
import com.weavo.crawler.collection.domain.rule.Navigation.PaginationType;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionCompleted;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionExecute;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionProjectResult;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.MessageError;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.ObjectReference;
import com.weavo.extraction.FieldValueProcessor;
import com.weavo.extraction.HtmlScope;
import com.weavo.siteaccess.BrowserSession;
import com.weavo.siteaccess.BrowserTab;
import com.weavo.siteaccess.SiteAccess;
import com.weavo.siteaccess.SiteAccess.PageSnapshot;
import com.weavo.siteaccess.SiteAccessException;
import com.weavo.siteaccess.SiteAccessException.Kind;
import com.weavo.siteaccess.SiteSession;
import com.weavo.siteaccess.WorkBudget;
import com.weavo.worker.application.AttemptContext;
import com.weavo.worker.application.JobProcessor.Job;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Collects one configured list and its current-tab details for a full collection attempt. */
public final class FixedCollection implements CollectionJobHandlers.Handler {
  private final SiteAccess siteAccess;
  private final FieldValueProcessor fields;
  private final String bucket;
  private final SnapshotUpload store;

  @FunctionalInterface
  public interface SnapshotUpload {
    void put(String key, String contentType, byte[] bytes);
  }

  public FixedCollection(
      SiteAccess siteAccess, FieldValueProcessor fields, String bucket, SnapshotUpload upload) {
    this.siteAccess = siteAccess;
    this.fields = fields;
    this.bucket = bucket;
    this.store = upload;
  }

  @Override
  public CollectionCompleted execute(Job<CollectionExecute> job, AttemptContext attempt)
      throws InterruptedException {
    var request = job.payload();
    var rules = new FixedCollectionFields(request.projects(), fields);
    if (request.projects().stream().anyMatch(project -> !project.collectionMode().equals("FULL"))) {
      return result(
          request,
          attempt,
          "TECHNICAL_FAILURE",
          null,
          null,
          null,
          error("TECHNICAL_FAILURE", "COLLECTION", "고정 수집 모드에서는 전체 수집만 지원합니다."));
    }
    if (request.navigation().pagination().type() != PaginationType.NONE
        || !request.navigation().initialActions().isEmpty()
        || rules.detailGroups().keySet().stream()
            .anyMatch(rule -> rule.open().target() != Target.CURRENT_TAB)) {
      return result(
          request,
          attempt,
          "NAVIGATION_FAILED",
          null,
          null,
          null,
          error("INVALID_NAVIGATION_RULE", "COLLECTION", "고정 수집에서 실행할 수 없는 이동 규칙입니다."));
    }

    var budget =
        new WorkBudget(
            attempt.workDeadline(), attempt::isCancellationRequested, attempt::requireWorkAllowed);
    try (var snapshot = new CollectionSnapshot(bucket, job.id(), attempt)) {
      var state = new CaptureState();
      String reason = "END_OF_LIST";
      try {
        collect(request, budget, rules, snapshot, state);
      } catch (SiteAccessException failure) {
        if (failure.kind() == Kind.CANCELLED) {
          throw new CancellationException("Fixed collection no longer owns this attempt");
        }
        if (failure.kind() != Kind.WORK_LIMIT) {
          return result(
              request,
              attempt,
              stopReason(failure.kind(), state),
              state.listCount,
              null,
              null,
              accessError(failure.kind(), state));
        }
        reason = "TIME_LIMIT";
      } catch (FixedCollectionFields.InvalidField failure) {
        return fieldFailure(request, attempt, state, failure);
      }
      if (state.listCount != null && state.listCount == 0) {
        return result(
            request,
            attempt,
            "EMPTY_LIST",
            0L,
            null,
            null,
            new MessageError(
                "EMPTY_LIST",
                "LIST_EXTRACTION",
                "반복 목록 항목을 찾지 못했습니다.",
                Map.of("observed_count", 0),
                false,
                null));
      }
      if (snapshot.itemCount() == 0) {
        return result(
            request,
            attempt,
            reason,
            state.listCount,
            null,
            null,
            error("TECHNICAL_FAILURE", state.stage, "수집 기한 안에 완료한 항목이 없습니다."));
      }

      long validated = snapshot.itemCount();
      try {
        var ref = snapshot.publish(request, store);
        return result(request, attempt, reason, state.listCount, validated, ref, null);
      } catch (CollectionSnapshot.UploadFailed failure) {
        attempt.requireOwnership();
        return result(
            request,
            attempt,
            "TECHNICAL_FAILURE",
            state.listCount,
            validated,
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
      CaptureState state) {
    Navigation navigation = request.navigation();
    SiteSession sites = siteAccess.open(budget);
    String selector = navigation.itemSelector();
    if (navigation.listAccess().method() == AccessMethod.HTTP) {
      PageSnapshot list = sites.fetchHtml(navigation.listUrl());
      state.stage = "LIST_EXTRACTION";
      var items = selectItems(list, selector);
      state.listCount = (long) items.size();
      if (items.isEmpty()) {
        return;
      }
      if (rules.detailGroups().isEmpty()) {
        captureItems(request, rules, budget, snapshot, state, list, items, null, null);
        return;
      }
      try (BrowserSession browser = sites.openBrowser();
          BrowserTab tab = browser.newTab()) {
        PageSnapshot rendered =
            tab.navigate(navigation.listUrl(), navigation.listAccess().waitSelector());
        if (!list.url().equals(rendered.url())) {
          throw new SiteAccessException(
              Kind.BROWSER_FAILED, "Browser and HTTP list addresses differ");
        }
        var browserList = BrowserList.capture(tab, rendered, selector);
        verifyBrowserItems(items, browserList.markup());
        captureItems(request, rules, budget, snapshot, state, list, items, tab, browserList);
      }
      return;
    }

    try (BrowserSession browser = sites.openBrowser();
        BrowserTab tab = browser.newTab()) {
      PageSnapshot list =
          tab.navigate(navigation.listUrl(), navigation.listAccess().waitSelector());
      state.stage = "LIST_EXTRACTION";
      var items = selectItems(list, selector);
      var browserList = BrowserList.capture(tab, list, selector);
      verifyBrowserItems(items, browserList.markup());
      state.listCount = (long) items.size();
      if (!items.isEmpty()) {
        captureItems(request, rules, budget, snapshot, state, list, items, tab, browserList);
      }
    }
  }

  private void captureItems(
      CollectionExecute request,
      FixedCollectionFields rules,
      WorkBudget budget,
      CollectionSnapshot snapshot,
      CaptureState state,
      PageSnapshot list,
      List<HtmlScope> items,
      BrowserTab tab,
      BrowserList initialBrowserList) {
    String selector = request.navigation().itemSelector();
    BrowserList current = initialBrowserList;
    var originalRows = List.<HtmlScope>of();
    if (initialBrowserList != null) {
      originalRows = initialBrowserList.document().select(selector);
      if (request.navigation().listAccess().method() == AccessMethod.HTTP) {
        verifyBrowserItems(originalRows, initialBrowserList.markup());
      }
    }
    snapshot.captureList(list);
    for (int index = 0; index < items.size(); index++) {
      state.stage = "LIST_EXTRACTION";
      budget.check();
      rules.validate(rules.listFields(), items.get(index), budget);
      var details = new ArrayList<CollectionSnapshot.Detail>();
      Map<String, List<String>> directLinks = new LinkedHashMap<>();
      for (var entry : rules.detailGroups().entrySet()) {
        state.stage = "DETAIL_NAVIGATION";
        var rule = entry.getKey();
        var originalRow = originalRows.get(index);
        String originalMarkup = initialBrowserList.markup().get(index);
        var browserItem = current.reselect(selector, index, originalRow, originalMarkup, rule);
        var detail = openDetail(tab, browserItem, rule);
        var page = detail.page();
        rules.validate(entry.getValue(), HtmlScope.parse(page.html()), budget);
        var consumers = FixedCollectionFields.consumers(entry.getValue());
        details.add(snapshot.captureDetail(page, consumers));
        if (detail.directUrl() != null && !detail.directUrl().equals(list.url())) {
          for (var consumer : consumers) {
            directLinks
                .computeIfAbsent(consumer.projectSourceId(), ignored -> new ArrayList<>())
                .add(detail.directUrl().toString());
          }
        }

        state.stage = "DETAIL_RETURN";
        var returned = tab.goBack(rule.returnRule().waitSelector());
        if (!returned.url().equals(list.url())) {
          throw new SiteAccessException(Kind.BROWSER_FAILED, "Detail did not return to the list");
        }
        current = BrowserList.capture(tab, returned, selector);
        current.reselect(selector, index, originalRow, originalMarkup, rule);
      }
      var urls =
          request.projects().stream()
              .map(project ->
                  new CollectionSnapshot.SourceUrl(
                      project.projectSourceId(),
                      uniqueLink(directLinks.get(project.projectSourceId()))))
              .toList();
      snapshot.addItem(index, urls, details);
    }
  }

  private static BrowserTab.DetailSnapshot openDetail(
      BrowserTab tab, BrowserTab.Item item, DetailNavigation rule) {
    var open = rule.open();
    return switch (open.type()) {
      case LINK ->
          tab.followLink(item, open.selector(), open.urlAttribute(), rule.access().waitSelector());
      case CLICK -> tab.clickDetail(item, open.selector(), rule.access().waitSelector());
    };
  }

  private static List<HtmlScope> selectItems(PageSnapshot list, String selector) {
    try {
      return HtmlScope.parse(list.html()).select(selector);
    } catch (IllegalArgumentException | IllegalStateException failure) {
      throw new SiteAccessException(Kind.BROWSER_FAILED, "Invalid list item selector");
    }
  }

  private static void verifyBrowserItems(List<HtmlScope> items, List<String> browserItems) {
    if (items.size() != browserItems.size()) {
      throw new SiteAccessException(
          Kind.BROWSER_FAILED, "Browser and stored list item counts differ");
    }
    for (int index = 0; index < items.size(); index++) {
      if (!items.get(index).matchesMarkup(browserItems.get(index))) {
        throw new SiteAccessException(Kind.BROWSER_FAILED, "Browser and stored list items differ");
      }
    }
  }

  private record BrowserList(PageSnapshot page, HtmlScope document, List<String> markup) {
    static BrowserList capture(BrowserTab tab, PageSnapshot page, String selector) {
      return new BrowserList(
          page, HtmlScope.parse(page.html(), page.url().toString()), tab.itemHtml(selector));
    }

    BrowserTab.Item reselect(
        String selector,
        int index,
        HtmlScope original,
        String originalMarkup,
        DetailNavigation rule) {
      try {
        HtmlScope actual = document.selectItem(selector, index);
        String actualMarkup = markup.get(index);
        if (actual.matchesMarkup(actualMarkup)
            && isSameItem(original, originalMarkup, actual, actualMarkup, rule)) {
          return new BrowserTab.Item(page.url().toString(), selector, index, actualMarkup);
        }
      } catch (IllegalArgumentException
          | IllegalStateException
          | IndexOutOfBoundsException failure) {
        throw unverifiedItem();
      }
      throw unverifiedItem();
    }

    private static boolean isSameItem(
        HtmlScope original,
        String originalMarkup,
        HtmlScope actual,
        String actualMarkup,
        DetailNavigation rule) {
      var open = rule.open();
      if (open.type() == OpenType.CLICK) {
        // A click may use data attributes or handlers without exposing a stable link.
        return originalMarkup.equals(actualMarkup);
      }
      String expected = original.uniqueLink(open.selector(), open.urlAttribute());
      return expected != null
          && original.hasSameText(actual)
          && expected.equals(actual.uniqueLink(open.selector(), open.urlAttribute()));
    }

    private static SiteAccessException unverifiedItem() {
      return new SiteAccessException(
          Kind.BROWSER_FAILED, "Could not reselect the original list item");
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

  private static CollectionCompleted fieldFailure(
      CollectionExecute request,
      AttemptContext attempt,
      CaptureState state,
      FixedCollectionFields.InvalidField failure) {
    var results =
        request.projects().stream()
            .map(project -> {
              MessageError projectError =
                  project.projectSourceId().equals(failure.projectSourceId())
                      ? failure.error()
                      : error("TECHNICAL_FAILURE", "COLLECTION", "출처 수집을 완료하지 못했습니다.");
              return new CollectionProjectResult(
                  project.projectId(),
                  project.projectSourceId(),
                  project.schemaVersion(),
                  project.connectorRevision(),
                  "FAILED",
                  false,
                  null,
                  projectError);
            })
            .toList();
    return new CollectionCompleted(
        attempt.id().toString(),
        request.sourceId(),
        request.navigationRevision(),
        "FAILED",
        "TECHNICAL_FAILURE",
        attempt.startedAt().toString(),
        attempt.now().toString(),
        state.listCount,
        null,
        results,
        failure.error());
  }

  private static CollectionCompleted result(
      CollectionExecute request,
      AttemptContext attempt,
      String stopReason,
      Long listCount,
      Long extracted,
      ObjectReference snapshot,
      MessageError error) {
    String status = error == null ? "SUCCEEDED" : "FAILED";
    var projects =
        request.projects().stream()
            .map(project ->
                new CollectionProjectResult(
                    project.projectId(),
                    project.projectSourceId(),
                    project.schemaVersion(),
                    project.connectorRevision(),
                    status,
                    snapshot != null,
                    extracted,
                    error))
            .toList();
    return new CollectionCompleted(
        attempt.id().toString(),
        request.sourceId(),
        request.navigationRevision(),
        status,
        stopReason,
        attempt.startedAt().toString(),
        attempt.now().toString(),
        listCount,
        snapshot,
        projects,
        error);
  }

  private static MessageError error(String code, String stage, String message) {
    return new MessageError(code, stage, message, Map.of(), false, null);
  }

  private static final class CaptureState {
    String stage = "LIST_ACCESS";
    Long listCount;
  }
}
