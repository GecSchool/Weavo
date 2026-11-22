package com.weavo.siteaccess;

import static com.weavo.siteaccess.SiteAccessException.Kind.ACCESS_BLOCKED;
import static com.weavo.siteaccess.SiteAccessException.Kind.CANCELLED;
import static com.weavo.siteaccess.SiteAccessException.Kind.INVALID_URL;
import static com.weavo.siteaccess.SiteAccessException.Kind.WORK_LIMIT;
import static com.weavo.siteaccess.SiteFixture.redirect;
import static com.weavo.siteaccess.SiteFixture.reply;
import static com.weavo.siteaccess.SiteFixture.work;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Tag("browser")
class BrowserSiteAccessTest {
  private static final Duration WORK = Duration.ofSeconds(12);

  @Test
  void rendersJavascriptAfterValidatingEachRedirectAndSubrequest() throws Exception {
    try (var fixture = new SiteFixture()) {
      fixture.route("/entry", request -> redirect(request, fixture.url("other.example", "/list")));
      fixture.route(
          "/list",
          request ->
              reply(
                  request,
                  200,
                  "text/html",
                  """
                  <link rel="stylesheet" href="/style.css"><h1>목록</h1>
                  <img src="/image.png"><script src="/app.js"></script>
                  """));
      fixture.route("/style.css", request -> reply(request, 200, "text/css", "h1 { color: red; }"));
      fixture.route("/app.js", request -> redirect(request, "/scripts/render.js"));
      fixture.route(
          "/scripts/render.js",
          request ->
              reply(
                  request,
                  200,
                  "text/javascript",
                  """
                  fetch('/data').then(response => response.text()).then(title => {
                    const article = document.createElement('article');
                    article.className = 'rendered';
                    article.textContent = title;
                    document.body.appendChild(article);
                  });
                  """));
      fixture.route("/data", request -> reply(request, 200, "text/plain; charset=UTF-8", "동적 데이터"));
      fixture.route("/image.png", request -> reply(request, 200, "image/png", "ignored"));
      List<String> permits = new ArrayList<>();
      var access =
          fixture.access((target, budget) -> {
            permits.add(target.getHost() + target.getPath());
            return () -> {};
          });
      try (var browser = access.open(work(WORK)).openBrowser();
          var tab = browser.newTab()) {
        var page = tab.navigate(fixture.url("/entry"), "article.rendered");

        assertThat(page.url()).isEqualTo(URI.create(fixture.url("other.example", "/list")));
        assertThat(page.html()).contains("<article class=\"rendered\">동적 데이터</article>");
        assertThat(permits)
            .contains(
                "site.example/robots.txt",
                "site.example/entry",
                "other.example/robots.txt",
                "other.example/list",
                "other.example/style.css",
                "other.example/app.js",
                "other.example/scripts/render.js",
                "other.example/data")
            .doesNotContain("other.example/image.png");
      }
    }
  }

  @Test
  void reportsBlockedAndUnsafeTargetsWithoutFollowingThem() throws Exception {
    try (var fixture = new SiteFixture()) {
      fixture.route("/entry", request -> redirect(request, "http://169.254.169.254/blocked"));
      fixture.route("/blocked", request -> reply(request, 401, "text/html", "Blocked"));
      try (var browser = fixture.access().open(work(WORK)).openBrowser();
          var tab = browser.newTab()) {
        assertThat(
                assertThrows(SiteAccessException.class, () -> tab.navigate(fixture.url("/entry")))
                    .kind())
            .isEqualTo(INVALID_URL);
        assertThat(
                assertThrows(SiteAccessException.class, () -> tab.navigate(fixture.url("/blocked")))
                    .kind())
            .isEqualTo(ACCESS_BLOCKED);
        assertThat(fixture.requests).containsExactly("/robots.txt", "/entry", "/blocked");
      }
    }
  }

  @ParameterizedTest
  @CsvSource({"401, false", "403, false", "403, true"})
  void reportsABlockedSourceDataRequestEvenIfThePageRendersFallbackHtml(
      int status, boolean redirectsToAnotherOrigin) throws Exception {
    try (var fixture = new SiteFixture()) {
      fixture.route(
          "/page",
          request ->
              reply(
                  request,
                  200,
                  "text/html",
                  """
                  <main><div id="list"></div></main><script>
                    fetch('/data').catch(() => document.querySelector('main').classList.add('request-failed'));
                  </script>
                  """));
      fixture.route(
          "/data",
          request -> {
            if (redirectsToAnotherOrigin) {
              redirect(request, fixture.url("other.example", "/denied"));
            } else {
              reply(request, status, "text/plain", "Blocked");
            }
          });
      fixture.route("/denied", request -> reply(request, status, "text/plain", "Blocked"));
      try (var browser = fixture.access().open(work(WORK)).openBrowser();
          var tab = browser.newTab()) {
        var failure =
            assertThrows(
                SiteAccessException.class,
                () -> tab.navigate(fixture.url("/page"), "main.request-failed"));

        assertThat(failure.kind()).isEqualTo(ACCESS_BLOCKED);
        assertThat(failure.status()).isEqualTo(status);
        assertThat(fixture.requests.stream().filter("/data"::equals)).hasSize(1);
        if (redirectsToAnotherOrigin) {
          assertThat(fixture.requests).contains("/denied");
        }
      }
    }
  }

  @Test
  void anUnrelatedThirdPartyBlockDoesNotTurnAValidPageIntoAnAccessBlock() throws Exception {
    try (var fixture = new SiteFixture()) {
      fixture.route(
          "/page",
          request ->
              reply(
                  request,
                  200,
                  "text/html",
                  """
                  <main>Public list</main><script>
                    fetch('%s').catch(() => document.querySelector('main').classList.add('ready'));
                  </script>
                  """
                      .formatted(fixture.url("other.example", "/third-party"))));
      fixture.route("/third-party", request -> reply(request, 403, "text/plain", "Blocked"));
      try (var browser = fixture.access().open(work(WORK)).openBrowser();
          var tab = browser.newTab()) {
        assertThat(tab.navigate(fixture.url("/page"), "main.ready").html()).contains("Public list");
        assertThat(fixture.requests).contains("/third-party");
      }
    }
  }

  @Test
  void aPageThatChecksConnectivityCanRenderItsResponse() throws Exception {
    try (var fixture = new SiteFixture()) {
      fixture.route(
          "/page",
          request ->
              reply(
                  request,
                  200,
                  "text/html",
                  """
                  <main></main><script>
                    if (navigator.onLine) {
                      fetch('/data').then(response => response.text()).then(text => {
                        const main = document.querySelector('main');
                        main.textContent = text;
                        main.classList.add('ready');
                      });
                    }
                  </script>
                  """));
      fixture.route("/data", request -> reply(request, 200, "text/plain", "Online dataset"));
      try (var browser = fixture.access().open(work(WORK)).openBrowser();
          var tab = browser.newTab()) {
        assertThat(tab.navigate(fixture.url("/page"), "main.ready").html())
            .contains("<main class=\"ready\">Online dataset</main>");
        assertThat(fixture.requests).contains("/data");
      }
    }
  }

  @Test
  void deniesDirectBrowserConnectionsEvenWithoutAnInterceptionHandler() throws Exception {
    try (var fixture = new SiteFixture();
        var playwright = Playwright.create();
        var browser = playwright.chromium().launch(BrowserSession.launchOptions(10_000))) {
      fixture.route("/direct", request -> reply(request, 200, "text/html", "A local test page"));
      var page = browser.newPage();

      var failure =
          assertThrows(
              PlaywrightException.class,
              () ->
                  page.navigate(
                      fixture.url("127.0.0.1", "/direct"),
                      new Page.NavigateOptions().setTimeout(2_000)));

      assertThat(failure).hasMessageContaining("net::ERR_");
      assertThat(fixture.requests).isEmpty();
    }
  }

  @Test
  void detectsARenderedChallengeWhileWaitingForTheRequestedSelector() throws Exception {
    try (var fixture = new SiteFixture()) {
      fixture.route(
          "/challenge",
          request ->
              reply(
                  request,
                  200,
                  "text/html",
                  """
                  <script>
                    setTimeout(() => {
                      document.title = 'Verify you are human';
                      const form = document.createElement('form');
                      form.id = 'challenge-form';
                      document.body.appendChild(form);
                    }, 100);
                  </script>
                  """));
      try (var browser = fixture.access().open(work(WORK)).openBrowser();
          var tab = browser.newTab()) {
        var failure =
            assertThrows(
                SiteAccessException.class,
                () -> tab.navigate(fixture.url("/challenge"), "article.missing"));
        assertThat(failure.kind()).isEqualTo(ACCESS_BLOCKED);
        assertThat(fixture.requests).containsExactly("/robots.txt", "/challenge");
      }
    }
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "WEAVO_SITE_TEST_REDIS_URL", matches = ".+")
  @EnabledIfEnvironmentVariable(named = "WEAVO_SITE_TEST_DB_URL", matches = ".+")
  void reusesTheSameHostLeaseForSequentialBrowserResourcesWithoutHoldingThePage() throws Exception {
    try (var fixture = new SiteFixture();
        var coordinator = SiteFixture.redisCoordinator()) {
      fixture.route("/entry", request -> redirect(request, "/list"));
      fixture.route(
          "/list",
          request ->
              reply(request, 200, "text/html", "<main></main><script src='/list.js'></script>"));
      fixture.route(
          "/list.js",
          request ->
              reply(
                  request,
                  200,
                  "text/javascript",
                  "document.querySelector('main').classList.add('ready')"));
      try (var browser = fixture.access(coordinator).open(work(WORK)).openBrowser();
          var tab = browser.newTab()) {
        assertThat(tab.navigate(fixture.url("/entry"), "main.ready").html())
            .contains("class=\"ready\"");
        assertThat(fixture.requests).containsExactly("/robots.txt", "/entry", "/list", "/list.js");
      }
    }
  }

  @Test
  void opensIndependentTabsAndAllowsAnExplicitResourceType() throws Exception {
    try (var fixture = new SiteFixture()) {
      fixture.route(
          "/list",
          request ->
              reply(
                  request,
                  200,
                  "text/html",
                  """
                  <img src="/image.png" onerror="document.body.classList.add('image-requested')">
                  """));
      fixture.route("/image.png", request -> reply(request, 404, "text/plain", ""));
      try (var browser =
          fixture.access().open(work(WORK)).openBrowser(Set.of(BrowserSession.Resource.IMAGE))) {
        try (var tab = browser.newTab()) {
          assertThat(tab.navigate(fixture.url("/list"), "body.image-requested").html())
              .contains("image-requested");
        }
        try (var tab = browser.newTab()) {
          assertThat(tab.navigate(fixture.url("/list"), "body.image-requested").html())
              .contains("image-requested");
        }
        assertThat(fixture.requests.stream().filter("/image.png"::equals)).hasSize(2);
      }
    }
  }

  @Test
  void stopsAnUnfinishedBrowserWaitAtTheWorkDeadline() throws Exception {
    try (var fixture = new SiteFixture()) {
      fixture.route("/list", request -> reply(request, 200, "text/html", "<h1>목록</h1>"));
      try (var browser = fixture.access().open(work(Duration.ofSeconds(4))).openBrowser();
          var tab = browser.newTab()) {
        var failure =
            assertThrows(
                SiteAccessException.class,
                () -> tab.navigate(fixture.url("/list"), "article.missing"));
        assertThat(failure.kind()).isEqualTo(WORK_LIMIT);
        assertThat(fixture.requests).contains("/list");
      }
    }
  }

  @Test
  void stopsAnUnfinishedBrowserWaitWhenItsJobIsCancelled() throws Exception {
    try (var fixture = new SiteFixture();
        var scheduler = Executors.newSingleThreadScheduledExecutor()) {
      var cancelled = new AtomicBoolean();
      fixture.route(
          "/list",
          request -> {
            reply(request, 200, "text/html", "<h1>목록</h1>");
            scheduler.schedule(() -> cancelled.set(true), 250, TimeUnit.MILLISECONDS);
          });
      try (var browser = fixture.access().open(work(WORK, cancelled::get)).openBrowser();
          var tab = browser.newTab()) {
        var failure =
            assertThrows(
                SiteAccessException.class,
                () -> tab.navigate(fixture.url("/list"), "article.missing"));
        assertThat(failure.kind()).isEqualTo(CANCELLED);
        assertThat(fixture.requests).contains("/list");
      }
    }
  }
}
