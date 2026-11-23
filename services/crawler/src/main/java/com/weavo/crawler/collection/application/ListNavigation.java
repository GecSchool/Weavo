package com.weavo.crawler.collection.application;

import com.weavo.crawler.collection.domain.rule.Navigation.Pagination;
import com.weavo.extraction.HtmlScope;
import com.weavo.siteaccess.SiteAccessException;
import com.weavo.siteaccess.SiteAccessException.Kind;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Advances URL-based rules and rejects a repeated set of list items. */
final class ListNavigation {
  private final Pagination rule;
  private final Set<List<String>> visited = new HashSet<>();
  private final Map<String, Integer> capturedOccurrences = new HashMap<>();
  private long pageOffset;

  ListNavigation(Pagination rule) {
    this.rule = rule;
  }

  void observePage(List<HtmlScope> items) {
    if (!visited.add(items.stream().map(HtmlScope::markupFingerprint).sorted().toList())) {
      throw failure("List navigation did not reach a new set of items");
    }
  }

  List<Integer> observeCumulativeList(List<HtmlScope> items) {
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

  boolean hasClickControl(HtmlScope document) {
    HtmlScope control = control(document);
    return control != null && !control.isDisabledControl();
  }

  String nextUrl(HtmlScope document) {
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
