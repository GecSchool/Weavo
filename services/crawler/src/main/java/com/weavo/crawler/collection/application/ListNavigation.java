package com.weavo.crawler.collection.application;

import com.weavo.crawler.collection.domain.rule.Navigation.Pagination;
import com.weavo.extraction.HtmlScope;
import com.weavo.siteaccess.SiteAccessException;
import com.weavo.siteaccess.SiteAccessException.Kind;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Advances URL-based rules and rejects a repeated set of list items. */
final class ListNavigation {
  private final Pagination rule;
  private final Set<List<String>> visited = new HashSet<>();
  private long pageOffset;

  ListNavigation(Pagination rule) {
    this.rule = rule;
  }

  void observePage(List<HtmlScope> items) {
    if (!visited.add(items.stream().map(HtmlScope::markupFingerprint).sorted().toList())) {
      throw failure("List navigation did not reach a new set of items");
    }
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
    final List<HtmlScope> matches;
    try {
      matches = document.select(rule.selector());
    } catch (IllegalArgumentException | IllegalStateException invalidSelector) {
      throw failure("The next-link selector is invalid");
    }
    if (matches.isEmpty()) {
      return null;
    }
    if (matches.size() != 1) {
      throw failure("The next-link selector did not identify a single control");
    }
    HtmlScope control = matches.getFirst();
    if (control.isDisabledControl()) {
      return null;
    }
    String url = control.absoluteUrl(rule.urlAttribute());
    if (url == null) {
      throw failure("The next control did not provide a URL");
    }
    return url;
  }

  private static SiteAccessException failure(String message) {
    return new SiteAccessException(Kind.BROWSER_FAILED, message);
  }
}
