package com.weavo.crawler;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Playwright;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "weavo.browser.smoke", matches = "true")
class BrowserSmokeTest {
  @Test
  void rendersLocalHtmlInChromium() {
    try (var playwright = Playwright.create();
        var browser = playwright.chromium().launch()) {
      var page = browser.newPage();
      page.setContent("<main><h1>Weavo</h1></main>");
      assertThat(page.locator("main h1").textContent()).isEqualTo("Weavo");
    }
  }
}
