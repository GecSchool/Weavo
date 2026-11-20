package com.weavo.siteaccess;

import static com.weavo.siteaccess.SiteAccessException.Kind.ACCESS_BLOCKED;
import static com.weavo.siteaccess.SiteAccessException.Kind.HTTP_STATUS;
import static com.weavo.siteaccess.SiteAccessException.Kind.HTTP_TIMEOUT;
import static com.weavo.siteaccess.SiteAccessException.Kind.REDIRECT_LIMIT;
import static com.weavo.siteaccess.SiteAccessException.Kind.ROBOTS_DENIED;
import static com.weavo.siteaccess.SiteAccessException.Kind.SITE_UNAVAILABLE;

import crawlercommons.robots.BaseRobotRules;
import crawlercommons.robots.SimpleRobotRules;
import crawlercommons.robots.SimpleRobotRulesParser;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLPeerUnverifiedException;
import okhttp3.Headers;
import okhttp3.HttpUrl;
import okhttp3.Request;

/** Keeps robots rules for the lifetime of one work attempt. Use from the worker thread. */
public final class SiteSession {
  static final int MAX_REDIRECTS = 5;
  private static final int ROBOTS_LIMIT = 512 * 1024;
  private static final String HTML_ACCEPT = "text/html,application/xhtml+xml;q=0.9,*/*;q=0.8";
  private static final List<Duration> RETRY_DELAYS =
      List.of(Duration.ofSeconds(2), Duration.ofSeconds(5));
  private final HttpTransport transport;
  private final WorkBudget budget;
  private final Map<HttpUrl, BaseRobotRules> robots = new HashMap<>();

  SiteSession(HttpTransport transport, WorkBudget budget) {
    this.transport = transport;
    this.budget = budget;
  }

  public SiteAccess.PageSnapshot fetchHtml(String url) {
    HttpUrl target = PublicUrls.parse(url);
    HttpTransport.Reply reply = follow(target, true, budget);
    requireOk(reply);
    String html = reply.html();
    budget.check();
    return new SiteAccess.PageSnapshot(reply.url().uri(), html);
  }

  public BrowserSession openBrowser() {
    return openBrowser(Set.of());
  }

  /** Allows only the named resources otherwise skipped by default. */
  public BrowserSession openBrowser(Set<BrowserSession.Resource> extraResources) {
    budget.check();
    return new BrowserSession(this, budget, Set.copyOf(extraResources));
  }

  HttpTransport.Reply browserRequest(
      HttpUrl url, String method, Headers headers, WorkBudget pageBudget) {
    requireRobotsAllowed(url, pageBudget);
    return send(url, method, headers, pageBudget, HttpTransport.HTML_LIMIT);
  }

  private HttpTransport.Reply follow(HttpUrl start, boolean page, WorkBudget work) {
    HttpUrl current = start;
    Headers headers = Headers.of("Accept", page ? HTML_ACCEPT : "text/plain,*/*;q=0.8");
    for (int redirects = 0; ; redirects++) {
      if (page) {
        requireRobotsAllowed(current, work);
      }
      var reply =
          send(current, "GET", headers, work, page ? HttpTransport.HTML_LIMIT : ROBOTS_LIMIT);
      if (!HttpTransport.isRedirect(reply.status())) {
        return reply;
      }
      if (redirects == MAX_REDIRECTS) {
        throw redirectLimit();
      }
      current = PublicUrls.redirect(current, reply.headers().get("Location"));
    }
  }

  private void requireRobotsAllowed(HttpUrl url, WorkBudget work) {
    HttpUrl originRules = url.newBuilder().encodedPath("/robots.txt").query(null).build();
    BaseRobotRules rules = robots.computeIfAbsent(originRules, target -> loadRobots(target, work));
    work.check();
    if (!rules.isAllowed(url.toString())) {
      throw new SiteAccessException(ROBOTS_DENIED, "The site's robots rules deny this path");
    }
  }

  private BaseRobotRules loadRobots(HttpUrl url, WorkBudget work) {
    HttpTransport.Reply reply = follow(url, false, work);
    if (reply.status() >= 400 && reply.status() < 500) {
      return new SimpleRobotRules(SimpleRobotRules.RobotRulesMode.ALLOW_ALL);
    }
    requireOk(reply);
    return new SimpleRobotRulesParser()
        .parseContent(
            url.toString(),
            reply.body(),
            reply.headers().get("Content-Type"),
            List.of(SiteAccess.PRODUCT_TOKEN));
  }

  private HttpTransport.Reply send(
      HttpUrl url, String method, Headers headers, WorkBudget work, int limit) {
    Request request =
        new Request.Builder()
            .url(url)
            .method(method, null)
            .headers(headers)
            .header("User-Agent", SiteAccess.USER_AGENT)
            .build();
    for (int attempt = 0; ; attempt++) {
      try {
        var reply = transport.send(request, work, limit);
        classifyAccess(reply);
        return reply;
      } catch (SiteAccessException failure) {
        if (attempt == RETRY_DELAYS.size() || !isRetryable(failure)) {
          throw failure;
        }
        work.pause(RETRY_DELAYS.get(attempt));
      }
    }
  }

  private static void classifyAccess(HttpTransport.Reply reply) {
    int status = reply.status();
    String prefix =
        new String(
            reply.body(), 0, Math.min(reply.body().length, 128 * 1024), StandardCharsets.UTF_8);
    if (HttpTransport.isImmediateBlock(status, reply.headers()) || isChallengePage(prefix)) {
      throw new SiteAccessException(ACCESS_BLOCKED, "Site access was blocked", status, null);
    }
    if (status == 408 || status == 429 || status >= 500 && status <= 599) {
      throw new SiteAccessException(
          SITE_UNAVAILABLE, "Site returned a temporary HTTP error", status, null);
    }
  }

  static boolean isChallengePage(String html) {
    String lower = html.toLowerCase(Locale.ROOT);
    return lower.contains("/cdn-cgi/challenge-platform/")
            && (lower.contains("<title>just a moment") || lower.contains("challenge-form"))
        || lower.contains("<form")
            && lower.contains("action=\"/sorry/")
            && lower.contains("recaptcha");
  }

  static void requireOk(HttpTransport.Reply reply) {
    int status = reply.status();
    if (status < 200 || status >= 300) {
      throw new SiteAccessException(
          HTTP_STATUS, "Site returned an unsuccessful HTTP status", status, null);
    }
  }

  static SiteAccessException redirectLimit() {
    return new SiteAccessException(REDIRECT_LIMIT, "Site exceeded the redirect limit");
  }

  private static boolean isRetryable(SiteAccessException failure) {
    return failure.kind() == HTTP_TIMEOUT
        || failure.kind() == SITE_UNAVAILABLE
            && !(failure.getCause() instanceof SSLHandshakeException)
            && !(failure.getCause() instanceof SSLPeerUnverifiedException);
  }
}
