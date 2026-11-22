package com.weavo.analysis.source.application;

import com.weavo.extraction.FieldExtractor;
import com.weavo.extraction.FieldValueProcessor;
import com.weavo.extraction.HtmlScope;
import com.weavo.siteaccess.BrowserList;
import com.weavo.siteaccess.BrowserSession;
import com.weavo.siteaccess.BrowserTab;
import com.weavo.siteaccess.ListNavigation;
import com.weavo.siteaccess.SiteAccess;
import com.weavo.siteaccess.SiteAccess.PageSnapshot;
import com.weavo.siteaccess.SiteAccessException;
import com.weavo.siteaccess.WorkBudget;
import com.weavo.siteaccess.rule.FieldConnector.DetailNavigation;
import com.weavo.siteaccess.rule.FieldConnector.Location;
import com.weavo.siteaccess.rule.Navigation;
import com.weavo.siteaccess.rule.Navigation.AccessMethod;
import com.weavo.siteaccess.rule.Navigation.PaginationType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Generates a source candidate and proves it against the same browser rules used by collection. */
public final class SourceAnalyzer {
  private static final int DETAIL_SAMPLE_LIMIT = 3;

  private final SiteAccess sites;
  private final FieldValueProcessor fields;
  private final SourceCandidateGenerator generator;

  public SourceAnalyzer(
      SiteAccess sites, FieldValueProcessor fields, SourceCandidateGenerator generator) {
    this.sites = sites;
    this.fields = fields;
    this.generator = generator;
  }

  public Result analyze(String url, String purpose, WorkBudget budget) throws InterruptedException {
    Failure failure = null;
    try (var browser = sites.open(budget).openBrowser();
        var tab = browser.newTab()) {
      var initial = tab.navigate(url);
      SourceCandidate previous = null;
      while (true) {
        budget.check();
        try {
          previous =
              generator.propose(
                  new SourceCandidateGenerator.Input(
                      url, purpose, initial.html(), previous, failure),
                  budget);
          budget.check();
          if (previous == null) {
            return new Result(
                null,
                List.of(),
                failure == null ? new Failure("CANDIDATE", null, "NO_PROPOSAL") : failure);
          }
          var candidate = browserCandidate(previous, url);
          return new Result(candidate, validate(browser, tab, candidate, budget), null);
        } catch (InvalidCandidate error) {
          failure = error.failure();
        } catch (CancellationException error) {
          throw error;
        } catch (IllegalArgumentException | IllegalStateException error) {
          failure = new Failure("CANDIDATE", null, "INVALID_CANDIDATE");
        } catch (SiteAccessException error) {
          if (!canCorrect(error.kind())) {
            return failed(error);
          }
          failure = new Failure("NAVIGATION", null, error.kind().name());
        }
      }
    } catch (SiteAccessException error) {
      if (error.kind() == SiteAccessException.Kind.WORK_LIMIT && failure != null) {
        return new Result(
            null, List.of(), new Failure("TIME_LIMIT", failure.candidateKey(), failure.reason()));
      }
      return failed(error);
    }
  }

  private static boolean canCorrect(SiteAccessException.Kind kind) {
    return switch (kind) {
      case INVALID_URL, BROWSER_FAILED, BROWSER_TIMEOUT, REDIRECT_LIMIT -> true;
      default -> false;
    };
  }

  private static Result failed(SiteAccessException error) {
    if (error.kind() == SiteAccessException.Kind.CANCELLED) {
      throw new CancellationException("Source analysis was cancelled");
    }
    String stage = error.kind() == SiteAccessException.Kind.WORK_LIMIT ? "TIME_LIMIT" : "ACCESS";
    return new Result(null, List.of(), new Failure(stage, null, error.kind().name()));
  }

  private static SourceCandidate browserCandidate(SourceCandidate generated, String requestedUrl) {
    var rule = generated.navigation();
    if (!Navigation.normalizedListUrl(requestedUrl)
        .equals(Navigation.normalizedListUrl(rule.listUrl()))) {
      throw invalid("LIST", null, "LIST_URL_CHANGED");
    }
    return new SourceCandidate(
        new Navigation(
            requestedUrl,
            rule.initialActions(),
            new Navigation.ListAccess(AccessMethod.BROWSER, rule.listAccess().waitSelector()),
            rule.itemSelector(),
            rule.pagination()),
        generated.fields());
  }

  private List<Map<String, Object>> validate(
      BrowserSession browser, BrowserTab tab, SourceCandidate candidate, WorkBudget budget) {
    var rule = candidate.navigation();
    var firstPage = openList(tab, rule);
    var first = checkedList(tab, firstPage, rule.itemSelector());
    var firstItems = first.document().select(rule.itemSelector());
    if (firstItems.isEmpty()) {
      throw invalid("LIST", null, "NO_LIST_ITEMS");
    }

    if (rule.pagination().type() == PaginationType.URL_TEMPLATE) {
      verifyTemplateOrigin(browser, rule, first);
    }

    Map<DetailNavigation, List<SourceCandidate.Field>> detailFields = new LinkedHashMap<>();
    for (var field : candidate.fields()) {
      if (field.connector().location() == Location.DETAIL) {
        detailFields
            .computeIfAbsent(field.connector().detailNavigation(), ignored -> new ArrayList<>())
            .add(field);
      }
    }

    if (detailFields.size() > DETAIL_SAMPLE_LIMIT) {
      throw invalid("DETAIL_NAVIGATION", null, "DETAIL_SAMPLE_LIMIT_EXCEEDED");
    }
    int limit =
        detailFields.isEmpty()
            ? firstItems.size()
            : Math.min(firstItems.size(), DETAIL_SAMPLE_LIMIT / detailFields.size());
    var samples = new ArrayList<Map<String, Object>>();
    var current = first;
    for (int index = 0; index < limit; index++) {
      budget.check();
      var values = listValues(candidate, firstItems.get(index));
      for (var entry : detailFields.entrySet()) {
        var detailRule = entry.getKey();
        var original = firstItems.get(index);
        var originalMarkup = first.markup().get(index);
        var item =
            current.reselect(rule.itemSelector(), index, original, originalMarkup, detailRule);
        var detail = tab.openDetail(item, detailRule);
        var scope = HtmlScope.parse(detail.page().html(), detail.page().url().toString());
        for (var field : entry.getValue()) {
          values.put(field.connector().fieldId(), extract(field, scope));
        }
        var returned = tab.returnToList(detailRule);
        if (!firstPage.url().equals(returned.url())) {
          throw invalid("DETAIL_RETURN", null, "LIST_URL_CHANGED");
        }
        current = checkedList(tab, returned, rule.itemSelector());
        current.reselect(rule.itemSelector(), index, original, originalMarkup, detailRule);
      }
      samples.add(Collections.unmodifiableMap(values));
    }

    verifyNextPage(tab, candidate, firstItems, current, budget);
    return List.copyOf(samples);
  }

  private static PageSnapshot openList(BrowserTab tab, Navigation rule) {
    String wait = rule.listAccess().waitSelector();
    var page = tab.navigate(rule.listUrl(), rule.initialActions().isEmpty() ? wait : null);
    for (var action : rule.initialActions()) {
      page = tab.clickList(action.selector(), action.waitSelector(), null);
    }
    return !rule.initialActions().isEmpty() && wait != null ? tab.awaitList(wait) : page;
  }

  private static BrowserList checkedList(BrowserTab tab, PageSnapshot page, String selector) {
    var list = BrowserList.capture(tab, page, selector);
    BrowserList.verifyItems(list.document().select(selector), list.markup());
    return list;
  }

  private static void verifyTemplateOrigin(
      BrowserSession browser, Navigation rule, BrowserList first) {
    var pagination = rule.pagination();
    String expanded = pagination.urlTemplate().replace("{value}", pagination.start().toString());
    String actual = Navigation.normalizedListUrl(first.page().url().toString());
    if (actual.equals(Navigation.normalizedListUrl(expanded))) {
      return;
    }
    try (var check = browser.newTab()) {
      var page = check.navigate(expanded, rule.listAccess().waitSelector());
      var equivalent = checkedList(check, page, rule.itemSelector());
      var expected =
          first.document().select(rule.itemSelector()).stream()
              .map(HtmlScope::markupFingerprint)
              .toList();
      var observed =
          equivalent.document().select(rule.itemSelector()).stream()
              .map(HtmlScope::markupFingerprint)
              .toList();
      if (!expected.equals(observed)) {
        throw invalid("LIST_NAVIGATION", null, "TEMPLATE_START_MISMATCH");
      }
    }
  }

  private void verifyNextPage(
      BrowserTab tab,
      SourceCandidate candidate,
      List<HtmlScope> firstItems,
      BrowserList first,
      WorkBudget budget) {
    var rule = candidate.navigation();
    var type = rule.pagination().type();
    if (type == PaginationType.NONE) {
      return;
    }

    var movement = new ListNavigation(rule.pagination());
    boolean cumulative = type == PaginationType.SCROLL || type == PaginationType.LOAD_MORE;
    if (cumulative) {
      movement.observeCumulativeList(firstItems);
    } else {
      movement.observePage(firstItems);
    }

    var next = movement.moveBrowser(tab, rule, first.document());
    if (next == null) {
      throw invalid("LIST_NAVIGATION", null, "NEXT_SCREEN_NOT_REACHED");
    }
    var nextList = checkedList(tab, next, rule.itemSelector());
    var nextItems = nextList.document().select(rule.itemSelector());
    if (nextItems.isEmpty()) {
      throw invalid("LIST_NAVIGATION", null, "NO_LIST_ITEMS");
    }

    if (cumulative) {
      var added = movement.observeCumulativeList(nextItems);
      if (added.isEmpty()) {
        throw invalid("LIST_NAVIGATION", null, "NO_NEW_LIST_ITEMS");
      }
      for (int index : added) {
        budget.check();
        listValues(candidate, nextItems.get(index));
      }
    } else {
      movement.observePage(nextItems);
      for (var item : nextItems) {
        budget.check();
        listValues(candidate, item);
      }
    }
  }

  private Map<String, Object> listValues(SourceCandidate candidate, HtmlScope scope) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (var field : candidate.fields()) {
      if (field.connector().location() != Location.DETAIL) {
        values.put(field.connector().fieldId(), extract(field, scope));
      }
    }
    return values;
  }

  private Object extract(SourceCandidate.Field field, HtmlScope scope) {
    var connector = field.connector();
    var extraction =
        connector.location() == Location.ABSENT
            ? FieldExtractor.absent()
            : switch (connector.extract().type()) {
              case TEXT -> FieldExtractor.extractText(scope, connector.selector());
              case ATTRIBUTE ->
                  FieldExtractor.extractAttribute(
                      scope, connector.selector(), connector.extract().attribute());
            };
    var result =
        fields.process(
            extraction,
            connector.parser() == null ? null : connector.parser().code(),
            field.type(),
            field.nullable());
    if (result.failure() != null) {
      throw invalid(
          result.failure().stage().name(), connector.fieldId(), result.failure().reason());
    }
    return result.value();
  }

  private static InvalidCandidate invalid(String stage, String field, String reason) {
    return new InvalidCandidate(new Failure(stage, field, reason));
  }

  private static final class InvalidCandidate extends RuntimeException {
    private final Failure failure;

    InvalidCandidate(Failure failure) {
      super(failure.reason());
      this.failure = failure;
    }

    Failure failure() {
      return failure;
    }
  }

  public record Failure(String stage, String candidateKey, String reason) {}

  public record Result(
      SourceCandidate candidate, List<Map<String, Object>> samples, Failure failure) {}
}
