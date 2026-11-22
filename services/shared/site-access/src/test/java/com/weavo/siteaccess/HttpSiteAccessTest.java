package com.weavo.siteaccess;

import static com.weavo.siteaccess.SiteAccessException.Kind.ACCESS_BLOCKED;
import static com.weavo.siteaccess.SiteAccessException.Kind.BROWSER_TIMEOUT;
import static com.weavo.siteaccess.SiteAccessException.Kind.CANCELLED;
import static com.weavo.siteaccess.SiteAccessException.Kind.INVALID_URL;
import static com.weavo.siteaccess.SiteAccessException.Kind.REDIRECT_LIMIT;
import static com.weavo.siteaccess.SiteAccessException.Kind.RESPONSE_TOO_LARGE;
import static com.weavo.siteaccess.SiteAccessException.Kind.ROBOTS_DENIED;
import static com.weavo.siteaccess.SiteAccessException.Kind.WORK_LIMIT;
import static com.weavo.siteaccess.SiteFixture.redirect;
import static com.weavo.siteaccess.SiteFixture.reply;
import static com.weavo.siteaccess.SiteFixture.work;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.InetAddress;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class HttpSiteAccessTest {
  private static final Duration WORK = Duration.ofSeconds(12);

  @Test
  void followsTheRedirectAndUsesOnePermitPerRequest() throws Exception {
    try (var fixture = new SiteFixture()) {
      fixture.route(
          "/entry",
          request -> {
            if (request.getRequestURI().getQuery() == null) {
              redirect(request, "?page=2");
            } else {
              reply(request, 200, "text/html; charset=UTF-8", "<h1>새 공지</h1>");
            }
          });
      List<String> lifecycle = new ArrayList<>();
      var access =
          fixture.access((target, budget) -> {
            lifecycle.add("open " + target.getPath());
            return () -> lifecycle.add("close " + target.getPath());
          });
      var page = access.open(work(WORK)).fetchHtml(fixture.url("/entry"));

      assertThat(page.url()).isEqualTo(URI.create(fixture.url("/entry?page=2")));
      assertThat(page.html()).isEqualTo("<h1>새 공지</h1>");
      assertThat(fixture.requests).containsExactly("/robots.txt", "/entry", "/entry?page=2");
      assertThat(fixture.userAgents).containsOnly("WeavoBot/1.0");
      assertThat(lifecycle)
          .containsExactly(
              "open /robots.txt",
              "close /robots.txt",
              "open /entry",
              "close /entry",
              "open /entry",
              "close /entry");
    }
  }

  @Test
  void appliesRobotsRulesBeforeTheRedirectTargetIsFetched() throws Exception {
    try (var fixture = new SiteFixture()) {
      fixture.robots("User-agent: *\nDisallow: /\n\nUser-agent: weavobot\nDisallow: /restricted\n");
      fixture.route("/entry", request -> redirect(request, "/restricted"));
      var failure =
          assertThrows(
              SiteAccessException.class,
              () -> fixture.access().open(work(WORK)).fetchHtml(fixture.url("/entry")));

      assertThat(failure.kind()).isEqualTo(ROBOTS_DENIED);
      assertThat(fixture.requests).containsExactly("/robots.txt", "/entry");
    }
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "file:///etc/hosts",
        "http://user@example.com/",
        "http://localhost/",
        "http://127.0.0.1/",
        "http://127.1/",
        "http://2130706433/",
        "http://169.254.169.254/",
        "http://[::ffff:127.0.0.1]/",
        "http://[fd00:ec2::254]/",
        "http://[fe80::1]/"
      })
  void rejectsNonPublicTargetsBeforeAcquiringAPermit(String value) {
    var entered = new AtomicBoolean();
    var access =
        new SiteAccess((target, budget) -> {
          entered.set(true);
          return () -> {};
        });
    var failure =
        assertThrows(SiteAccessException.class, () -> access.open(work(WORK)).fetchHtml(value));
    assertThat(failure.kind()).isEqualTo(INVALID_URL);
    assertThat(entered).isFalse();
  }

  @Test
  void rejectsMixedPublicAndPrivateDnsResults() {
    assertThatThrownBy(() ->
            PublicUrls.resolvePublic(
                "site.example",
                name ->
                    List.of(
                        InetAddress.getByAddress(new byte[] {8, 8, 8, 8}),
                        InetAddress.getByAddress(new byte[] {10, 1, 1, 1}))))
        .isInstanceOf(PublicUrls.UnsafeHost.class);
  }

  @Test
  void followsAtMostFiveRedirectsAndRejectsUnsafeRedirects() throws Exception {
    try (var fixture = new SiteFixture()) {
      fixture.route("/loop", request -> redirect(request, "/loop"));
      fixture.route("/invalid", request -> redirect(request, "http://metadata.example/private"));
      var sites = fixture.access().open(work(WORK));
      var loop =
          assertThrows(SiteAccessException.class, () -> sites.fetchHtml(fixture.url("/loop")));
      var invalid =
          assertThrows(SiteAccessException.class, () -> sites.fetchHtml(fixture.url("/invalid")));

      assertThat(loop.kind()).isEqualTo(REDIRECT_LIMIT);
      assertThat(invalid.kind()).isEqualTo(INVALID_URL);
      assertThat(fixture.requests.stream().filter("/loop"::equals)).hasSize(6);
      assertThat(fixture.requests).doesNotContain("/private");
    }
  }

  @Test
  void waitsTwoAndThenFiveSecondsBetweenTemporaryErrors() throws Exception {
    try (var fixture = new SiteFixture()) {
      var arrivals = new java.util.concurrent.ConcurrentLinkedQueue<Long>();
      var count = new AtomicInteger();
      fixture.route(
          "/feed",
          request -> {
            arrivals.add(System.nanoTime());
            int attempt = count.incrementAndGet();
            int status = attempt == 1 ? 503 : attempt == 2 ? 429 : 200;
            request.getResponseHeaders().set("Retry-After", "0");
            reply(request, status, "text/html", "<h1>OK</h1>");
          });
      var page = fixture.access().open(work(WORK)).fetchHtml(fixture.url("/feed"));

      assertThat(page.html()).contains("OK");
      assertThat(arrivals).hasSize(3);
      var times = List.copyOf(arrivals);
      assertThat(Duration.ofNanos(times.get(1) - times.get(0)))
          .isGreaterThanOrEqualTo(Duration.ofSeconds(2));
      assertThat(Duration.ofNanos(times.get(2) - times.get(1)))
          .isGreaterThanOrEqualTo(Duration.ofSeconds(5));
    }
  }

  @Test
  void doesNotRetryAnAccessBlockOrChallenge() throws Exception {
    try (var fixture = new SiteFixture()) {
      fixture.route("/forbidden", request -> reply(request, 403, "text/html", "Forbidden"));
      fixture.route(
          "/challenge",
          request -> {
            request.getResponseHeaders().set("cf-mitigated", "challenge");
            reply(request, 503, "text/html", "Challenge");
          });
      var sites = fixture.access().open(work(WORK));
      for (String path : List.of("/forbidden", "/challenge")) {
        assertThat(
                assertThrows(SiteAccessException.class, () -> sites.fetchHtml(fixture.url(path)))
                    .kind())
            .isEqualTo(ACCESS_BLOCKED);
      }
      assertThat(fixture.requests).containsExactly("/robots.txt", "/forbidden", "/challenge");
    }
  }

  @Test
  void cancelsAPendingReplyAndReleasesThePermit() throws Exception {
    try (var fixture = new SiteFixture();
        var scheduler = Executors.newSingleThreadScheduledExecutor()) {
      var cancelled = new AtomicBoolean();
      var held = new AtomicInteger();
      var released = new CountDownLatch(1);
      fixture.route(
          "/slow",
          request -> {
            scheduler.schedule(() -> cancelled.set(true), 50, TimeUnit.MILLISECONDS);
            request.sendResponseHeaders(200, 0);
            request.getResponseBody().write(1);
            request.getResponseBody().flush();
            try {
              Thread.sleep(Duration.ofSeconds(4));
            } catch (InterruptedException error) {
              Thread.currentThread().interrupt();
            }
          });
      var sites =
          fixture
              .access((target, budget) -> {
                held.incrementAndGet();
                return () -> {
                  held.decrementAndGet();
                  if (cancelled.get()) {
                    released.countDown();
                  }
                };
              })
              .open(work(WORK, cancelled::get));
      var failure =
          assertThrows(SiteAccessException.class, () -> sites.fetchHtml(fixture.url("/slow")));

      assertThat(failure.kind()).isEqualTo(CANCELLED);
      assertThat(released.await(2, TimeUnit.SECONDS)).isTrue();
      assertThat(held).hasValue(0);
      assertThat(fixture.requests).containsExactly("/robots.txt", "/slow");
    }
  }

  @Test
  void stopsRetryWaitAtTheWorkDeadline() throws Exception {
    try (var fixture = new SiteFixture()) {
      fixture.route("/retry", request -> reply(request, 429, "text/plain", "Slow down"));
      var sites = fixture.access().open(work(Duration.ofMillis(750)));
      var failure =
          assertThrows(SiteAccessException.class, () -> sites.fetchHtml(fixture.url("/retry")));

      assertThat(failure.kind()).isEqualTo(WORK_LIMIT);
      assertThat(fixture.requests).containsExactly("/robots.txt", "/retry");
    }
  }

  @Test
  void passesTheShorterOperationDeadlineToTheWorkPermission() {
    var receivedDeadline = new AtomicReference<Instant>();
    var workDeadline = Instant.now().plus(WORK);
    var budget =
        new WorkBudget(
            workDeadline,
            () -> false,
            deadline -> {
              receivedDeadline.set(deadline);
              throw new TimeoutException("Still waiting for authorization");
            });

    var pageBudget = budget.cap(Duration.ofSeconds(1), BROWSER_TIMEOUT);
    var failure = assertThrows(SiteAccessException.class, pageBudget::beforeRequest);

    assertThat(receivedDeadline.get()).isBefore(workDeadline);
    assertThat(failure.kind()).isEqualTo(BROWSER_TIMEOUT);
  }

  @Test
  void rejectsAChunkedBodyOverTheByteLimit() throws Exception {
    try (var fixture = new SiteFixture()) {
      fixture.route(
          "/large",
          request -> {
            request.sendResponseHeaders(200, 0);
            byte[] chunk = new byte[1024];
            for (int index = 0; index <= HttpTransport.HTML_LIMIT / chunk.length; index++) {
              request.getResponseBody().write(chunk);
            }
          });
      var sites = fixture.access().open(work(WORK));
      var failure =
          assertThrows(SiteAccessException.class, () -> sites.fetchHtml(fixture.url("/large")));

      assertThat(failure.kind()).isEqualTo(RESPONSE_TOO_LARGE);
      assertThat(fixture.requests).containsExactly("/robots.txt", "/large");
    }
  }
}
