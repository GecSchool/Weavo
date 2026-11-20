package com.weavo.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Playwright;
import com.weavo.extraction.FieldExtractor.Result;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("browser")
class BrowserExtractionTest {
  @Test
  void extractsTheSameTextAndAttributesFromTheBrowserAndStoredMarkup() throws IOException {
    String fixture;
    try (var input = getClass().getResourceAsStream("/field-extraction.html")) {
      fixture = new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
    try (var playwright = Playwright.create();
        var browser = playwright.chromium().launch()) {
      var page = browser.newPage();
      page.setContent(fixture);
      var original = HtmlScope.parse(fixture);
      var snapshot = HtmlScope.parse(page.content());
      for (var rule :
          List.of(
              new Rule("#notices", "article.notice > p.title", ""),
              new Rule("#notices", "article:nth-of-type(2) .title, .notice .empty", ""),
              new Rule("#first", "a.link[href]", "href"),
              new Rule("#first", "a.literal", "abs:href"),
              new Rule("#first", ".unmatched", ""))) {
        Object observed =
            page.evaluate(
                """
                ({ root, selector, attr }) => Array.from(
                    document.querySelector(root).querySelectorAll(selector),
                    node => attr === "" ? node.textContent : node.getAttribute(attr)
                )
                """,
                Map.of("root", rule.root(), "selector", rule.selector(), "attr", rule.attribute()));
        Result fromOriginal = extract(original, rule);
        Result fromSnapshot = extract(snapshot, rule);
        assertThat(fromSnapshot).as("selector %s", rule.selector()).isEqualTo(fromOriginal);
        assertThat(fromSnapshot.rawValues()).as("selector %s", rule.selector()).isEqualTo(observed);
        assertThat(fromSnapshot.matchCount()).isEqualTo(((List<?>) observed).size());
      }
    }
  }

  private static Result extract(HtmlScope document, Rule rule) {
    var root = document.select(rule.root()).getFirst();
    return rule.attribute().isEmpty()
        ? FieldExtractor.extractText(root, rule.selector())
        : FieldExtractor.extractAttribute(root, rule.selector(), rule.attribute());
  }

  private record Rule(String root, String selector, String attribute) {}
}
