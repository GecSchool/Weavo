package com.weavo.analysis.source.application;

import com.weavo.analysis.source.application.SourceCandidateGenerator.DetailField;
import com.weavo.analysis.source.application.SourceCandidateGenerator.DetailInput;
import com.weavo.analysis.source.application.SourceCandidateGenerator.DetailSample;
import com.weavo.analysis.source.application.SourceCandidateGenerator.DetailTarget;
import com.weavo.extraction.FieldExtractor;
import com.weavo.extraction.FieldValueProcessor;
import com.weavo.extraction.HtmlScope;
import com.weavo.siteaccess.BrowserList;
import com.weavo.siteaccess.BrowserSession;
import com.weavo.siteaccess.BrowserTab;
import com.weavo.siteaccess.BrowserTab.DetailSnapshot;
import com.weavo.siteaccess.ListNavigation;
import com.weavo.siteaccess.SiteAccess;
import com.weavo.siteaccess.SiteAccess.PageSnapshot;
import com.weavo.siteaccess.SiteAccessException;
import com.weavo.siteaccess.WorkBudget;
import com.weavo.siteaccess.rule.FieldConnector;
import com.weavo.siteaccess.rule.FieldConnector.DetailNavigation;
import com.weavo.siteaccess.rule.FieldConnector.ExtractType;
import com.weavo.siteaccess.rule.FieldConnector.Location;
import com.weavo.siteaccess.rule.FieldConnector.Parser;
import com.weavo.siteaccess.rule.Navigation;
import com.weavo.siteaccess.rule.Navigation.AccessMethod;
import com.weavo.siteaccess.rule.Navigation.PaginationType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import org.jsoup.Jsoup;
import org.jsoup.select.Selector.SelectorParseException;

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
    Map<String, String> observedScreens = new LinkedHashMap<>();
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
                      url, purpose, initial.html(), previous, failure, Map.copyOf(observedScreens)),
                  budget);
          budget.check();
          if (previous == null) {
            return new Result(
                null,
                List.of(),
                failure == null ? new Failure("CANDIDATE", null, "NO_PROPOSAL") : failure);
          }
          var candidate = browserCandidate(previous, url);
          return validate(browser, tab, candidate, budget, observedScreens, new Capture(), purpose);
        } catch (SourceCandidateGenerator.Unavailable error) {
          return new Result(null, List.of(), new Failure("LLM_CALL", null, error.getMessage()));
        } catch (FieldValueProcessor.ParserUnavailable error) {
          return new Result(null, List.of(), new Failure("PARSER_CALL", null, error.getMessage()));
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

  /** Replays mapped rules, records field evidence and then probes the optional HTTP list path. */
  public Verification verify(SourceCandidate configured, WorkBudget budget) {
    var candidate = browserCandidate(configured, configured.navigation().listUrl());
    var capture = new Capture();
    var session = sites.open(budget);
    try (var browser = session.openBrowser();
        var tab = browser.newTab()) {
      validate(browser, tab, candidate, budget, new LinkedHashMap<>(), capture, null);
      var rule = candidate.navigation();
      if (httpEquivalent(session, candidate, capture, budget)) {
        rule =
            new Navigation(
                rule.listUrl(),
                rule.initialActions(),
                new Navigation.ListAccess(AccessMethod.HTTP, null),
                rule.itemSelector(),
                rule.pagination());
      }
      return new Verification(rule, List.copyOf(capture.samples), null);
    } catch (InvalidCandidate error) {
      return new Verification(null, List.copyOf(capture.samples), error.failure());
    } catch (FieldValueProcessor.ParserUnavailable error) {
      return new Verification(
          null, List.copyOf(capture.samples), new Failure("PARSER_CALL", null, error.getMessage()));
    } catch (SiteAccessException error) {
      var failure = failed(error).failure();
      return new Verification(null, List.copyOf(capture.samples), failure);
    } catch (IllegalArgumentException | IllegalStateException error) {
      return new Verification(
          null, List.copyOf(capture.samples), new Failure("CANDIDATE", null, "INVALID_CANDIDATE"));
    }
  }

  private boolean httpEquivalent(
      com.weavo.siteaccess.SiteSession session,
      SourceCandidate candidate,
      Capture capture,
      WorkBudget budget) {
    var rule = candidate.navigation();
    if (!rule.initialActions().isEmpty()
        || !List.of(PaginationType.NONE, PaginationType.NEXT_LINK, PaginationType.URL_TEMPLATE)
            .contains(rule.pagination().type())) {
      return false;
    }
    try {
      var http = session.fetchHtml(rule.listUrl());
      if (!sameList(candidate, capture.first, http, budget)) {
        return false;
      }
      var movement = new ListNavigation(rule.pagination());
      String next = movement.nextUrl(HtmlScope.parse(http.html(), http.url().toString()));
      if (capture.next == null) {
        return next == null;
      }
      return next != null && sameList(candidate, capture.next, session.fetchHtml(next), budget);
    } catch (SiteAccessException error) {
      if (error.kind() == SiteAccessException.Kind.CANCELLED) {
        throw new CancellationException("Final verification was cancelled");
      }
      return false;
    } catch (InvalidCandidate
        | FieldValueProcessor.ParserUnavailable
        | IllegalArgumentException failure) {
      return false;
    }
  }

  private boolean sameList(
      SourceCandidate candidate, PageSnapshot browser, PageSnapshot http, WorkBudget budget) {
    if (!browser.url().equals(http.url())) {
      return false;
    }
    String selector = candidate.navigation().itemSelector();
    var before = HtmlScope.parse(browser.html(), browser.url().toString()).select(selector);
    var after = HtmlScope.parse(http.html(), http.url().toString()).select(selector);
    if (before.size() != after.size()) {
      return false;
    }
    for (int i = 0; i < before.size(); i++) {
      budget.check();
      if (!before.get(i).markupFingerprint().equals(after.get(i).markupFingerprint())
          || !listValues(candidate, before.get(i), new ArrayList<>())
              .equals(listValues(candidate, after.get(i), new ArrayList<>()))) {
        return false;
      }
    }
    return true;
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

  private Result validate(
      BrowserSession browser,
      BrowserTab tab,
      SourceCandidate candidate,
      WorkBudget budget,
      Map<String, String> observedScreens,
      Capture capture,
      String discoveryPurpose) {
    var rule = candidate.navigation();
    var firstPage = openList(tab, rule);
    capture.first = firstPage;
    var first = checkedList(tab, firstPage, rule.itemSelector());
    var firstItems = first.document().select(rule.itemSelector());
    if (firstItems.isEmpty()) {
      throw invalid("LIST", null, "NO_LIST_ITEMS");
    }

    if (rule.pagination().type() == PaginationType.URL_TEMPLATE) {
      verifyTemplateOrigin(browser, rule, first);
    }

    var detailFields = detailFields(candidate);
    if (detailFields.size() > DETAIL_SAMPLE_LIMIT) {
      throw invalid("DETAIL_NAVIGATION", null, "DETAIL_SAMPLE_LIMIT_EXCEEDED");
    }
    int limit =
        detailFields.isEmpty()
            ? Math.min(firstItems.size(), 50)
            : Math.min(firstItems.size(), DETAIL_SAMPLE_LIMIT / detailFields.size());
    var samples = new ArrayList<Map<String, Object>>();
    var current = first;
    for (int index = 0; index < limit; index++) {
      budget.check();
      var observed = new ArrayList<FieldObservation>();
      var links = new LinkedHashSet<String>();
      try {
        var values = listValues(candidate, firstItems.get(index), observed);
        Map<DetailNavigation, DetailSnapshot> detailSamples = Map.of();
        if (index == 0 && discoveryPurpose != null && !detailFields.isEmpty()) {
          var refined =
              refineDetails(
                  tab, candidate, discoveryPurpose, first, detailFields, observedScreens, budget);
          candidate = refined.candidate();
          detailFields = detailFields(candidate);
          detailSamples = refined.details();
          current = refined.list();
        }
        for (var entry : detailFields.entrySet()) {
          var detailRule = entry.getKey();
          var sampled = detailSamples.get(detailRule);
          var detail =
              sampled != null
                  ? sampled
                  : tab.openDetail(
                      current.reselect(
                          rule.itemSelector(),
                          index,
                          firstItems.get(index),
                          first.markup().get(index),
                          detailRule),
                      detailRule);
          if (detail.directUrl() != null && !detail.directUrl().equals(firstPage.url())) {
            links.add(detail.directUrl().toString());
          }
          observedScreens.put("DETAIL", detail.page().html());
          var scope = HtmlScope.parse(detail.page().html(), detail.page().url().toString());
          for (var field : entry.getValue()) {
            values.put(field.connector().fieldId(), extract(field, scope, observed));
          }
          if (sampled == null) {
            current =
                restoreList(
                    tab, first, rule.itemSelector(), index, firstItems.get(index), detailRule);
          }
        }
        samples.add(Collections.unmodifiableMap(values));
      } catch (InvalidCandidate failure) {
        if (!observed.isEmpty()) {
          capture.samples.add(
              new Sample(
                  links.size() == 1 ? links.getFirst() : null, List.copyOf(observed), false));
        }
        throw failure;
      }
      capture.samples.add(
          new Sample(links.size() == 1 ? links.getFirst() : null, List.copyOf(observed), true));
    }

    verifyNextPage(
        tab,
        candidate,
        firstItems,
        current,
        budget,
        observedScreens,
        capture,
        discoveryPurpose == null);
    return new Result(candidate, List.copyOf(samples), null);
  }

  private static Map<DetailNavigation, List<SourceCandidate.Field>> detailFields(
      SourceCandidate candidate) {
    Map<DetailNavigation, List<SourceCandidate.Field>> fields = new LinkedHashMap<>();
    for (var field : candidate.fields()) {
      if (field.connector().location() == Location.DETAIL) {
        fields
            .computeIfAbsent(field.connector().detailNavigation(), rule -> new ArrayList<>())
            .add(field);
      }
    }
    return fields;
  }

  private RefinedDetails refineDetails(
      BrowserTab tab,
      SourceCandidate candidate,
      String purpose,
      BrowserList first,
      Map<DetailNavigation, List<SourceCandidate.Field>> fields,
      Map<String, String> observedScreens,
      WorkBudget budget) {
    String itemSelector = candidate.navigation().itemSelector();
    var firstItem = first.document().selectItem(itemSelector, 0);
    var samples = new ArrayList<DetailSample>();
    Map<DetailNavigation, DetailSnapshot> snapshots = new LinkedHashMap<>();
    var current = first;
    for (var entry : fields.entrySet()) {
      budget.check();
      var rule = entry.getKey();
      var detail =
          tab.openDetail(
              current.reselect(itemSelector, 0, firstItem, first.markup().getFirst(), rule), rule);
      var html = detail.page().html();
      observedScreens.put("DETAIL", html);
      snapshots.put(rule, detail);
      samples.add(
          new DetailSample(
              html,
              entry.getValue().stream()
                  .map(field ->
                      new DetailTarget(
                          field.connector().fieldId(),
                          field.name(),
                          field.type().name(),
                          field.nullable(),
                          field.reason()))
                  .toList()));
      current = restoreList(tab, first, itemSelector, 0, firstItem, rule);
    }
    var proposals = generator.refineDetails(new DetailInput(purpose, List.copyOf(samples)), budget);
    budget.check();
    Map<String, DetailField> byId = new LinkedHashMap<>();
    for (var proposal : proposals) {
      if (byId.putIfAbsent(proposal.fieldId(), proposal) != null) {
        throw invalid("EXTRACTION", proposal.fieldId(), "DUPLICATE_DETAIL_FIELD");
      }
    }
    var updated = new ArrayList<SourceCandidate.Field>();
    for (var field : candidate.fields()) {
      var connector = field.connector();
      if (connector.location() != Location.DETAIL) {
        updated.add(field);
        continue;
      }
      var proposal = byId.remove(connector.fieldId());
      if (proposal == null) {
        throw invalid("EXTRACTION", connector.fieldId(), "DETAIL_FIELD_NOT_REFINED");
      }
      var scoped =
          new FieldConnector(
              connector.fieldId(),
              Location.DETAIL,
              proposal.selector(),
              proposal.extract(),
              proposal.parserCode() == null ? null : new Parser(proposal.parserCode()),
              connector.detailNavigation());
      var evidence = snapshots.get(connector.detailNavigation());
      if (scoped.extract().type() == ExtractType.TEXT
          && selectsPageRoot(evidence.page().html(), scoped.selector())) {
        throw invalid("EXTRACTION", connector.fieldId(), "DETAIL_SELECTOR_MATCHES_PAGE_ROOT");
      }
      updated.add(
          new SourceCandidate.Field(
              field.name(), field.type(), field.nullable(), field.reason(), scoped));
    }
    if (!byId.isEmpty()) {
      throw invalid("EXTRACTION", byId.keySet().iterator().next(), "UNKNOWN_DETAIL_FIELD");
    }
    return new RefinedDetails(
        new SourceCandidate(candidate.navigation(), updated), current, Map.copyOf(snapshots));
  }

  private static boolean selectsPageRoot(String html, String selector) {
    try {
      return Jsoup.parse(html).select(selector).stream()
          .anyMatch(element ->
              element.normalName().equals("html") || element.normalName().equals("body"));
    } catch (SelectorParseException ignored) {
      // The normal field extractor reports the invalid selector and captures its evidence.
      return false;
    }
  }

  private static BrowserList restoreList(
      BrowserTab tab,
      BrowserList first,
      String selector,
      int index,
      HtmlScope item,
      DetailNavigation rule) {
    var returned = tab.returnToList(rule);
    if (!first.page().url().equals(returned.url())) {
      throw invalid("DETAIL_RETURN", null, "LIST_URL_CHANGED");
    }
    var list = checkedList(tab, returned, selector);
    list.reselect(selector, index, item, first.markup().get(index), rule);
    return list;
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
      WorkBudget budget,
      Map<String, String> observedScreens,
      Capture capture,
      boolean allowDisabledNext) {
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
      if (allowDisabledNext && movement.hasDisabledNextControl(first.document())) {
        return;
      }
      throw invalid("LIST_NAVIGATION", null, "NEXT_SCREEN_NOT_REACHED");
    }
    observedScreens.put("NEXT_LIST", next.html());
    capture.next = next;
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
        checkNextRow(candidate, nextItems.get(index), capture);
      }
    } else {
      movement.observePage(nextItems);
      for (var item : nextItems) {
        budget.check();
        checkNextRow(candidate, item, capture);
      }
    }
  }

  private void checkNextRow(SourceCandidate candidate, HtmlScope row, Capture capture) {
    var observed = new ArrayList<FieldObservation>();
    try {
      listValues(candidate, row, observed);
    } catch (InvalidCandidate error) {
      if (!observed.isEmpty()) {
        capture.samples.add(new Sample(null, List.copyOf(observed), false));
      }
      throw error;
    }
  }

  private Map<String, Object> listValues(
      SourceCandidate candidate, HtmlScope scope, List<FieldObservation> observed) {
    Map<String, Object> values = new LinkedHashMap<>();
    for (var field : candidate.fields()) {
      if (field.connector().location() != Location.DETAIL) {
        values.put(field.connector().fieldId(), extract(field, scope, observed));
      }
    }
    return values;
  }

  private Object extract(
      SourceCandidate.Field field, HtmlScope scope, List<FieldObservation> observed) {
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
    observed.add(new FieldObservation(connector.fieldId(), result));
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

  private static final class Capture {
    PageSnapshot first;
    PageSnapshot next;
    final List<Sample> samples = new ArrayList<>();
  }

  private record RefinedDetails(
      SourceCandidate candidate, BrowserList list, Map<DetailNavigation, DetailSnapshot> details) {}

  public record FieldObservation(String fieldId, FieldValueProcessor.Result result) {}

  public record Sample(String sourceUrl, List<FieldObservation> fields, boolean complete) {}

  public record Verification(Navigation navigation, List<Sample> samples, Failure failure) {}

  public record Failure(String stage, String candidateKey, String reason) {}

  public record Result(
      SourceCandidate candidate, List<Map<String, Object>> samples, Failure failure) {}
}
