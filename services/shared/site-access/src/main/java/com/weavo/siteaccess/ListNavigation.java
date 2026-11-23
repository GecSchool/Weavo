package com.weavo.siteaccess;

import com.weavo.extraction.HtmlScope;
import com.weavo.siteaccess.SiteAccess.PageSnapshot;
import com.weavo.siteaccess.SiteAccessException.Kind;
import com.weavo.siteaccess.rule.Navigation;
import com.weavo.siteaccess.rule.Navigation.Pagination;
import com.weavo.siteaccess.rule.Navigation.PaginationType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Advances URL-based rules and rejects a repeated set of list items. */
public final class ListNavigation {
  private final Pagination rule;
  private final Set<List<String>> visited = new HashSet<>();
  private final Map<String, Integer> capturedOccurrences = new HashMap<>();
  private long pageOffset;

  public ListNavigation(Pagination rule) {
    this.rule = rule;
  }

  public void observePage(List<HtmlScope> items) {
    if (!visited.add(items.stream().map(HtmlScope::markupFingerprint).sorted().toList())) {
      throw failure("List navigation did not reach a new set of items");
    }
  }

  public List<Integer> observeCumulativeList(List<HtmlScope> items) {
    var occurrences = new HashMap<String, Integer>();
    var indices = new ArrayList<Integer>();
    for (int index = 0; index < items.size(); index++) {
      String key = items.get(index).cumulativeFingerprint();
      int occurrence = occurrences.merge(key, 1, Integer::sum);
      int previous = capturedOccurrences.getOrDefault(key, 0);
      if (occurrence > previous) {
        indices.add(index);
        capturedOccurrences.put(key, occurrence);
      }
    }
    return indices;
  }

  private boolean hasClickControl(HtmlScope document) {
    HtmlScope control = control(document);
    return control != null && !control.isDisabledControl();
  }

  public boolean hasDisabledNextControl(HtmlScope document) {
    if (!List.of(PaginationType.NEXT_LINK, PaginationType.CLICK_NEXT, PaginationType.LOAD_MORE)
        .contains(rule.type())) {
      return false;
    }
    HtmlScope next = control(document);
    return next != null && next.isDisabledControl();
  }

  public String nextUrl(HtmlScope document) {
    return switch (rule.type()) {
      case NONE -> null;
      case NEXT_LINK -> resolveNextLink(document);
      case URL_TEMPLATE -> {
        // The approved list URL opens the first page, which corresponds to start.
        long next =
            Math.addExact(rule.start(), Math.multiplyExact(++pageOffset, (long) rule.step()));
        yield rule.urlTemplate().replace("{value}", Long.toString(next));
      }
      default -> throw failure("This list rule does not use URL navigation");
    };
  }

  public PageSnapshot moveBrowser(BrowserTab tab, Navigation nav, HtmlScope current) {
    var rule = nav.pagination();
    return switch (rule.type()) {
      case NONE -> null;
      case NEXT_LINK, URL_TEMPLATE -> {
        String next = nextUrl(current);
        String wait =
            rule.waitSelector() == null ? nav.listAccess().waitSelector() : rule.waitSelector();
        yield next == null ? null : tab.navigate(next, wait);
      }
      case CLICK_NEXT, LOAD_MORE ->
          hasClickControl(current)
              ? tab.clickList(rule.selector(), rule.waitSelector(), nav.itemSelector())
              : null;
      case SCROLL ->
          tab.scrollList(rule.scrollContainerSelector(), rule.waitSelector(), nav.itemSelector());
    };
  }

  private String resolveNextLink(HtmlScope document) {
    HtmlScope control = control(document);
    if (control == null || control.isDisabledControl()) {
      return null;
    }
    String url = control.absoluteUrl(rule.urlAttribute());
    if (url == null) {
      throw failure("The next control did not provide a URL");
    }
    return url;
  }

  private HtmlScope control(HtmlScope document) {
    final List<HtmlScope> matches;
    try {
      matches = document.select(rule.selector());
    } catch (IllegalArgumentException | IllegalStateException invalidSelector) {
      throw failure("The list control selector is invalid");
    }
    if (matches.size() > 1) {
      throw failure("The list control selector did not identify a single control");
    }
    return matches.isEmpty() ? null : matches.getFirst();
  }

  private static SiteAccessException failure(String message) {
    return new SiteAccessException(Kind.BROWSER_FAILED, message);
  }
}
