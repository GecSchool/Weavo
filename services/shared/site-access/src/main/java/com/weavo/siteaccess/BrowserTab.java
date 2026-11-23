package com.weavo.siteaccess;

import static com.weavo.siteaccess.SiteAccessException.Kind.ACCESS_BLOCKED;
import static com.weavo.siteaccess.SiteAccessException.Kind.BROWSER_FAILED;
import static com.weavo.siteaccess.SiteAccessException.Kind.BROWSER_TIMEOUT;
import static com.weavo.siteaccess.SiteAccessException.Kind.REQUEST_COORDINATION_FAILED;
import static com.weavo.siteaccess.SiteAccessException.Kind.RESPONSE_TOO_LARGE;
import static com.weavo.siteaccess.SiteAccessException.Kind.ROBOTS_DENIED;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.microsoft.playwright.CDPSession;
import com.microsoft.playwright.ElementHandle;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.TimeoutError;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.WaitUntilState;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongConsumer;
import java.util.function.Supplier;
import okhttp3.Headers;
import okhttp3.HttpUrl;

/** A controlled Playwright page. All outbound HTTP is performed by the shared transport. */
public final class BrowserTab implements AutoCloseable {
  private static final Set<String> READ_ONLY_METHODS = Set.of("GET", "HEAD", "OPTIONS");
  private static final Set<String> DATA_REQUEST_TYPES = Set.of("Fetch", "XHR");
  private static final Set<String> REQUEST_HEADERS =
      Set.of(
          "accept",
          "accept-language",
          "origin",
          "referer",
          "access-control-request-method",
          "access-control-request-headers",
          "range");
  private static final Set<String> HOP_HEADERS =
      Set.of(
          "connection",
          "transfer-encoding",
          "upgrade",
          "keep-alive",
          "proxy-authenticate",
          "proxy-authorization",
          "trailer",
          "content-length",
          "set-cookie",
          "alt-svc");
  private static final String CAPTURE_HTML =
      """
      ({ selector, limit, progress }) => {
        const title = document.title.toLowerCase();
        const challenge = document.querySelector(
            'form#challenge-form, form[action^="/sorry/"], .h-captcha, .g-recaptcha, .cf-turnstile');
        if (challenge && /just a moment|verify you are human|captcha verification|security check/.test(title)) {
          return { blocked: true };
        }
        const ready = selector ? document.querySelector(selector) : null;
        if (selector && !ready) return null;
        if (progress && (ready?.outerHTML ?? "") === progress.marker) {
          const rows = Array.from(document.querySelectorAll(progress.selector), item => item.outerHTML);
          if (rows.length === progress.rows.length && rows.every((row, i) => row === progress.rows[i])) {
            return null;
          }
        }
        const html = document.documentElement?.outerHTML;
        if (html === undefined) return null;
        return new TextEncoder().encode(html).length > limit ? { oversized: true } : { html };
      }
      """;

  private final BrowserSession session;
  private final SiteSession sites;
  private final WorkBudget budget;
  private final Set<BrowserSession.Resource> extraResources;
  private final Page page;
  private final CDPSession devTools;
  private final String mainFrameId;
  private final Map<String, Redirect> redirectsByFetchId = new HashMap<>();
  private final Map<String, Redirect> redirectsByNetworkId = new HashMap<>();
  private WorkBudget navigation;
  private HttpUrl documentUrl;
  private SiteAccessException navigationFailure;

  private record Redirect(String fetchId, String networkId, int count, boolean sourceData) {}

  BrowserTab(
      BrowserSession session,
      SiteSession sites,
      WorkBudget budget,
      Set<BrowserSession.Resource> extraResources,
      Page page,
      CDPSession devTools) {
    this.session = session;
    this.sites = sites;
    this.budget = budget;
    this.extraResources = extraResources;
    this.page = page;
    this.devTools = devTools;
    this.mainFrameId =
        devTools
            .send("Page.getFrameTree")
            .getAsJsonObject("frameTree")
            .getAsJsonObject("frame")
            .get("id")
            .getAsString();
    devTools.on("Fetch.requestPaused", this::handleRequest);
    // Unlike Playwright routing, Fetch gives Chromium's redirected requests their own intercept.
    devTools.send("Fetch.enable");
  }

  public SiteAccess.PageSnapshot navigate(String url) {
    return navigate(url, null);
  }

  public SiteAccess.PageSnapshot navigate(String url, String waitSelector) {
    return move(() -> navigateTo(url), waitSelector).page();
  }

  /** Finds the exact previously captured item before following its configured detail link. */
  public DetailSnapshot followLink(
      Item item, String selector, String attribute, String waitSelector) {
    return openDetail(
        item,
        selector,
        element -> {
          var url =
              (String)
                  element.evaluate(
                      "(element, attribute) => {"
                          + " const value = element.getAttribute(attribute);"
                          + " return value?.trim() ? new URL(value, document.baseURI).href : null;"
                          + " }",
                      attribute);
          if (url == null) {
            throw new SiteAccessException(BROWSER_FAILED, "Detail link has no address");
          }
          return navigateTo(url);
        },
        waitSelector);
  }

  /** Clicks a verified item control and confirms current-tab document navigation. */
  public DetailSnapshot clickDetail(Item item, String selector, String waitSelector) {
    return openDetail(
        item,
        selector,
        element -> {
          page.waitForNavigation(
              new Page.WaitForNavigationOptions()
                  .setWaitUntil(WaitUntilState.COMMIT)
                  .setTimeout(navigation.timeoutMillis()),
              () ->
                  element.click(
                      new ElementHandle.ClickOptions().setTimeout(navigation.timeoutMillis())));
          return null;
        },
        waitSelector);
  }

  public SiteAccess.PageSnapshot goBack(String waitSelector) {
    return move(
            () -> {
              page.goBack(
                  new Page.GoBackOptions()
                      .setWaitUntil(WaitUntilState.COMMIT)
                      .setTimeout(navigation.timeoutMillis()));
              return null;
            },
            waitSelector)
        .page();
  }

  /** Runs an approved list click, keeping same-document network requests under the usual guard. */
  public SiteAccess.PageSnapshot clickList(
      String selector, String waitSelector, String itemSelector) {
    return listAction(
        () ->
            page.locator("css=" + selector)
                .click(new Locator.ClickOptions().setTimeout(navigation.timeoutMillis())),
        waitSelector,
        itemSelector);
  }

  /** Scrolls the document or the unique configured container towards its current end. */
  public SiteAccess.PageSnapshot scrollList(
      String container, String waitSelector, String itemSelector) {
    return listAction(
        () -> {
          boolean found =
              Boolean.TRUE.equals(
                  page.evaluate(
                      """
                      selector => {
                        const matches = selector ? document.querySelectorAll(selector) : [];
                        if (selector && matches.length !== 1) return false;
                        const target = selector ? matches[0] : document.scrollingElement;
                        if (!target) return false;
                        target.scrollTop = target.scrollHeight;
                        return true;
                      }
                      """,
                      container));
          if (!found) {
            throw new SiteAccessException(
                BROWSER_FAILED, "Scroll container could not be identified");
          }
        },
        waitSelector,
        itemSelector);
  }

  /** Confirms the list-level readiness rule after applying initial actions. */
  public SiteAccess.PageSnapshot awaitList(String waitSelector) {
    return move(() -> null, waitSelector, true, null).page();
  }

  private SiteAccess.PageSnapshot listAction(
      Runnable action, String waitSelector, String itemSelector) {
    budget.check();
    Map<String, Object> progress = null;
    if (itemSelector != null) {
      try {
        var marker =
            (String)
                page.evaluate(
                    "selector => selector ? document.querySelector(selector)?.outerHTML ?? '' : ''",
                    waitSelector);
        progress =
            Map.of("selector", itemSelector, "rows", itemHtml(itemSelector), "marker", marker);
      } catch (PlaywrightException error) {
        throw new SiteAccessException(BROWSER_FAILED, "Could not inspect a list action", error);
      }
    }
    return move(
            () -> {
              action.run();
              return null;
            },
            waitSelector,
            true,
            progress)
        .page();
  }

  /** Uses the browser's CSS engine and document order to check the captured list selection. */
  public List<String> itemHtml(String selector) {
    budget.check();
    try {
      @SuppressWarnings("unchecked")
      List<String> items =
          (List<String>)
              page.evaluate(
                  "selector => Array.from(document.querySelectorAll(selector), item => item.outerHTML)",
                  selector);
      budget.check();
      return List.copyOf(items);
    } catch (PlaywrightException error) {
      throw new SiteAccessException(BROWSER_FAILED, "Could not select browser list items", error);
    }
  }

  private DetailSnapshot openDetail(
      Item item, String selector, Function<ElementHandle, URI> action, String waitSelector) {
    return move(
        () -> {
          int tabCount = page.context().pages().size();
          // Read the item and its target together, so a changed index cannot select another record.
          var handle =
              page.evaluateHandle(
                  "({url, itemSelector, index, html, selector}) => {"
                      + " if (document.URL.split('#', 1)[0] !== url) return null;"
                      + " const item = document.querySelectorAll(itemSelector)[index];"
                      + " if (item?.outerHTML !== html) return null;"
                      + " const matches = item.querySelectorAll(selector);"
                      + " return matches.length === 1 ? matches[0] : null;"
                      + " }",
                  Map.of(
                      "url",
                      item.pageUrl(),
                      "itemSelector",
                      item.selector(),
                      "index",
                      item.index(),
                      "html",
                      item.html(),
                      "selector",
                      selector));
          try {
            var element = handle.asElement();
            if (element == null) {
              throw new SiteAccessException(BROWSER_FAILED, "Detail target could not be verified");
            }
            URI directUrl = action.apply(element);
            ensureNavigation();
            if (documentUrl == null || page.context().pages().size() != tabCount) {
              throw new SiteAccessException(
                  BROWSER_FAILED, "Detail did not open in the current tab");
            }
            return directUrl;
          } finally {
            handle.dispose();
          }
        },
        waitSelector);
  }

  private URI navigateTo(String url) {
    HttpUrl target = PublicUrls.parse(url);
    page.navigate(
        target.toString(),
        new Page.NavigateOptions()
            .setWaitUntil(WaitUntilState.COMMIT)
            .setTimeout(navigation.timeoutMillis()));
    return target.uri();
  }

  private DetailSnapshot move(Supplier<URI> action, String waitSelector) {
    return move(action, waitSelector, false, null);
  }

  private DetailSnapshot move(
      Supplier<URI> action,
      String waitSelector,
      boolean existingDocument,
      Map<String, Object> progress) {
    navigation = budget.cap(BrowserSession.PAGE_TIMEOUT, BROWSER_TIMEOUT);
    navigationFailure = null;
    try {
      documentUrl = existingDocument ? PublicUrls.parse(page.url()) : null;
      URI directUrl = action.get();
      waitUntil(timeout ->
          page.waitForLoadState(
              LoadState.DOMCONTENTLOADED, new Page.WaitForLoadStateOptions().setTimeout(timeout)));
      String html = captureHtml(waitSelector, progress);
      if (progress == null) {
        ensureNavigation();
      } else {
        if (navigationFailure != null) {
          throw navigationFailure;
        }
        budget.check();
      }
      if (SiteSession.isChallengePage(html)) {
        throw new SiteAccessException(ACCESS_BLOCKED, "The rendered page is a challenge");
      }
      return new DetailSnapshot(
          new SiteAccess.PageSnapshot(PublicUrls.parse(page.url()).uri(), html), directUrl);
    } catch (PlaywrightException error) {
      if (navigationFailure != null) {
        throw stopIfNeeded(navigationFailure);
      }
      try {
        navigation.check();
      } catch (SiteAccessException failure) {
        throw stopIfNeeded(failure);
      }
      var kind = error instanceof TimeoutError ? BROWSER_TIMEOUT : BROWSER_FAILED;
      throw new SiteAccessException(kind, "Browser navigation did not complete", error);
    } catch (SiteAccessException failure) {
      throw stopIfNeeded(failure);
    } finally {
      navigation = null;
      redirectsByFetchId.clear();
      redirectsByNetworkId.clear();
    }
  }

  private void handleRequest(JsonObject event) {
    String id = event.get("requestId").getAsString();
    var command = new JsonObject();
    command.addProperty("requestId", id);
    String resourceType = event.get("resourceType").getAsString();
    boolean mainFrame = mainFrameId.equals(string(event, "frameId"));
    boolean document = mainFrame && "Document".equals(resourceType);
    var data = event.getAsJsonObject("request");
    String method = data.get("method").getAsString();
    if (navigation == null
        || navigationFailure != null
        || skip(resourceType)
        || !READ_ONLY_METHODS.contains(method)) {
      failRequest(command);
      return;
    }
    String networkId = string(event, "networkId");
    Redirect previous = takePreviousRedirect(string(event, "redirectedRequestId"), networkId);
    boolean sourceData = previous != null && previous.sourceData();
    try {
      HttpUrl url = PublicUrls.parse(data.get("url").getAsString());
      if (document) {
        documentUrl = url;
      }
      if (mainFrame && DATA_REQUEST_TYPES.contains(resourceType) && sameOriginAsDocument(url)) {
        sourceData = true;
      }
      var reply =
          sites.browserRequest(
              url, method, readRequestHeaders(data.getAsJsonObject("headers")), navigation);
      HttpUrl redirectTarget = null;
      if (HttpTransport.isRedirect(reply.status())) {
        int redirectCount = previous == null ? 1 : previous.count() + 1;
        if (redirectCount > SiteSession.MAX_REDIRECTS) {
          throw SiteSession.redirectLimit();
        }
        redirectTarget = PublicUrls.redirect(url, reply.headers().get("Location"));
        var redirect = new Redirect(id, networkId, redirectCount, sourceData);
        redirectsByFetchId.put(id, redirect);
        if (networkId != null) {
          redirectsByNetworkId.put(networkId, redirect);
        }
      } else if (document) {
        SiteSession.requireOk(reply);
      }
      fulfill(command, reply, redirectTarget);
    } catch (SiteAccessException failure) {
      boolean denied = failure.kind() == ACCESS_BLOCKED || failure.kind() == ROBOTS_DENIED;
      if (navigationFailure == null
          && (document || sourceData && denied || failure.kind() == REQUEST_COORDINATION_FAILED)) {
        navigationFailure = failure;
      }
      failRequest(command);
    }
  }

  private Redirect takePreviousRedirect(String fetchId, String networkId) {
    var previous = redirectsByFetchId.get(fetchId);
    if (previous == null) {
      // A cross-origin XHR can get a new Fetch ID while keeping its Network ID.
      previous = redirectsByNetworkId.get(networkId);
    }
    if (previous != null) {
      redirectsByFetchId.remove(previous.fetchId());
      redirectsByNetworkId.remove(previous.networkId());
    }
    return previous;
  }

  private boolean sameOriginAsDocument(HttpUrl url) {
    return documentUrl != null
        && documentUrl.scheme().equals(url.scheme())
        && documentUrl.host().equals(url.host())
        && documentUrl.port() == url.port();
  }

  private void fulfill(JsonObject command, HttpTransport.Reply reply, HttpUrl redirectTarget) {
    var headers = new JsonArray();
    for (var name : reply.headers().names()) {
      if (!HOP_HEADERS.contains(name.toLowerCase(Locale.ROOT))
          && !(redirectTarget != null && name.equalsIgnoreCase("Location"))) {
        for (var value : reply.headers().values(name)) {
          addHeader(headers, name, value);
        }
      }
    }
    if (redirectTarget != null) {
      addHeader(headers, "Location", redirectTarget.toString());
    }
    command.addProperty("responseCode", reply.status());
    command.add("responseHeaders", headers);
    command.addProperty("body", Base64.getEncoder().encodeToString(reply.body()));
    devTools.send("Fetch.fulfillRequest", command);
  }

  private static void addHeader(JsonArray target, String name, String value) {
    var header = new JsonObject();
    header.addProperty("name", name);
    header.addProperty("value", value);
    target.add(header);
  }

  private static Headers readRequestHeaders(JsonObject values) {
    var headers = new Headers.Builder();
    values
        .entrySet()
        .forEach(entry -> {
          if (REQUEST_HEADERS.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
            headers.add(entry.getKey(), entry.getValue().getAsString());
          }
        });
    return headers.build();
  }

  private boolean skip(String type) {
    return switch (type) {
      case "Image" -> !extraResources.contains(BrowserSession.Resource.IMAGE);
      case "Media" -> !extraResources.contains(BrowserSession.Resource.MEDIA);
      case "Font" -> !extraResources.contains(BrowserSession.Resource.FONT);
      default -> false;
    };
  }

  private void failRequest(JsonObject command) {
    command.addProperty("errorReason", "BlockedByClient");
    devTools.send("Fetch.failRequest", command);
  }

  private void waitUntil(LongConsumer wait) {
    while (true) {
      ensureNavigation();
      try {
        wait.accept(Math.min(100, navigation.timeoutMillis()));
        return;
      } catch (TimeoutError ignored) {
        // Short waits give the controlling job a chance to cancel between browser operations.
      }
    }
  }

  private String captureHtml(String waitSelector, Map<String, Object> progress) {
    Map<String, Object> ready =
        Map.of(
            "selector",
            waitSelector == null ? "" : waitSelector,
            "limit",
            HttpTransport.HTML_LIMIT);
    Map<String, Object> arguments = new HashMap<>(ready);
    if (progress != null) {
      arguments.put("progress", progress);
    }
    try {
      return waitForHtml(arguments);
    } catch (SiteAccessException failure) {
      if (progress == null || failure.kind() != BROWSER_TIMEOUT || navigationFailure != null) {
        throw failure;
      }
      // A ready but unchanged list is a real observation, not a failed wait.
      budget.check();
      var observed = (Map<?, ?>) page.evaluate(CAPTURE_HTML, ready);
      if (observed == null) {
        throw failure;
      }
      return readDom(observed);
    }
  }

  private String waitForHtml(Map<String, Object> arguments) {
    while (true) {
      ensureNavigation();
      try {
        var handle =
            page.waitForFunction(
                CAPTURE_HTML,
                arguments,
                new Page.WaitForFunctionOptions()
                    .setTimeout(Math.min(100, navigation.timeoutMillis())));
        try {
          return readDom((Map<?, ?>) handle.jsonValue());
        } finally {
          handle.dispose();
        }
      } catch (TimeoutError ignored) {
        // Observe cancellation while waiting for a selector, a challenge, or the rendered DOM.
      }
    }
  }

  private static String readDom(Map<?, ?> result) {
    if (result.containsKey("blocked")) {
      throw new SiteAccessException(ACCESS_BLOCKED, "The rendered page is a challenge");
    }
    if (result.containsKey("oversized")) {
      throw new SiteAccessException(RESPONSE_TOO_LARGE, "Rendered HTML exceeded the byte limit");
    }
    String html = (String) result.get("html");
    if (html.getBytes(StandardCharsets.UTF_8).length > HttpTransport.HTML_LIMIT) {
      throw new SiteAccessException(RESPONSE_TOO_LARGE, "Rendered HTML exceeded the byte limit");
    }
    return html;
  }

  private void ensureNavigation() {
    if (navigationFailure != null) {
      throw navigationFailure;
    }
    navigation.check();
  }

  private SiteAccessException stopIfNeeded(SiteAccessException failure) {
    session.closeIfStopped(failure);
    return failure;
  }

  private static String string(JsonObject object, String key) {
    JsonElement value = object.get(key);
    return value == null || value.isJsonNull() ? null : value.getAsString();
  }

  /** The requested link is present only after successful link navigation. */
  public record DetailSnapshot(SiteAccess.PageSnapshot page, URI directUrl) {
    @Override
    public String toString() {
      return "DetailSnapshot";
    }
  }

  public record Item(String pageUrl, String selector, int index, String html) {
    @Override
    public String toString() {
      return "BrowserTab.Item[index=" + index + "]";
    }
  }

  @Override
  public void close() {
    if (!session.isClosed() && !page.isClosed()) {
      page.close();
    }
  }
}
