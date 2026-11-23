package com.weavo.siteaccess;

import com.weavo.extraction.HtmlScope;
import com.weavo.siteaccess.SiteAccess.PageSnapshot;
import com.weavo.siteaccess.SiteAccessException.Kind;
import com.weavo.siteaccess.rule.FieldConnector.DetailNavigation;
import com.weavo.siteaccess.rule.FieldConnector.OpenType;
import com.weavo.siteaccess.rule.FieldConnector.Target;
import java.util.List;

/** Pairs replayable list HTML with the browser's ordered rows for the same selector. */
public record BrowserList(PageSnapshot page, HtmlScope document, List<String> markup) {

  public static void verifyItems(List<HtmlScope> items, List<String> browserItems) {
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

  public static BrowserList capture(BrowserTab tab, PageSnapshot page, String selector) {
    return new BrowserList(
        page, HtmlScope.parse(page.html(), page.url().toString()), tab.itemHtml(selector));
  }

  public BrowserTab.Item reselect(
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
    } catch (IllegalArgumentException | IllegalStateException | IndexOutOfBoundsException failure) {
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
    if (open.type() == OpenType.CLICK || open.target() == Target.MODAL) {
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
