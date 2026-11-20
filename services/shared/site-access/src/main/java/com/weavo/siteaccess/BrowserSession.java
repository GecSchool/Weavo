package com.weavo.siteaccess;

import static com.weavo.siteaccess.SiteAccessException.Kind.BROWSER_FAILED;
import static com.weavo.siteaccess.SiteAccessException.Kind.BROWSER_TIMEOUT;
import static com.weavo.siteaccess.SiteAccessException.Kind.CANCELLED;
import static com.weavo.siteaccess.SiteAccessException.Kind.WORK_LIMIT;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One isolated Chromium context. Close it from the same thread that opened it. */
public final class BrowserSession implements AutoCloseable {
  public enum Resource {
    IMAGE,
    MEDIA,
    FONT
  }

  static final Duration PAGE_TIMEOUT = Duration.ofSeconds(30);
  private final SiteSession sites;
  private final WorkBudget budget;
  private final Set<Resource> extraResources;
  private final Playwright playwright;
  private final Browser browser;
  private final BrowserContext context;
  private boolean closed;

  BrowserSession(SiteSession sites, WorkBudget budget, Set<Resource> extraResources) {
    this.sites = sites;
    this.budget = budget;
    this.extraResources = extraResources;
    this.playwright = createPlaywright();
    try {
      var startup = budget.cap(PAGE_TIMEOUT, BROWSER_TIMEOUT);
      browser = playwright.chromium().launch(launchOptions(startup.timeoutMillis()));
      context =
          browser.newContext(
              new Browser.NewContextOptions()
                  .setServiceWorkers(ServiceWorkerPolicy.BLOCK)
                  .setAcceptDownloads(false)
                  .setUserAgent(SiteAccess.USER_AGENT));
      startup.check();
    } catch (RuntimeException error) {
      closePlaywright(playwright, error);
      if (error instanceof SiteAccessException failure) {
        throw failure;
      }
      throw new SiteAccessException(BROWSER_FAILED, "Could not start Chromium", error);
    }
  }

  static BrowserType.LaunchOptions launchOptions(long timeoutMillis) {
    return new BrowserType.LaunchOptions()
        .setTimeout(timeoutMillis)
        .setChromiumSandbox(true)
        .setArgs(
            List.of(
                // Unhandled requests fail closed. Intercepted responses still render normally.
                "--proxy-server=http://weavo-egress-block.invalid:1",
                "--proxy-bypass-list=<-loopback>",
                "--host-resolver-rules=MAP * ~NOTFOUND",
                "--disable-quic",
                "--force-webrtc-ip-handling-policy=disable_non_proxied_udp"));
  }

  public BrowserTab newTab() {
    try {
      budget.check();
    } catch (SiteAccessException failure) {
      closeIfStopped(failure);
      throw failure;
    }
    if (closed) {
      throw new SiteAccessException(BROWSER_FAILED, "Browser session is closed");
    }
    try {
      var page = context.newPage();
      try {
        return new BrowserTab(
            this, sites, budget, extraResources, page, context.newCDPSession(page));
      } catch (RuntimeException error) {
        try {
          page.close();
        } catch (RuntimeException closeError) {
          error.addSuppressed(closeError);
        }
        throw error;
      }
    } catch (PlaywrightException error) {
      throw new SiteAccessException(BROWSER_FAILED, "Could not open a browser tab", error);
    }
  }

  boolean isClosed() {
    return closed;
  }

  void closeIfStopped(SiteAccessException failure) {
    if (failure.kind() == CANCELLED || failure.kind() == WORK_LIMIT) {
      try {
        close();
      } catch (RuntimeException error) {
        failure.addSuppressed(error);
      }
    }
  }

  @Override
  public void close() {
    if (!closed) {
      closed = true;
      try (playwright;
          browser;
          context) {
        // Close in reverse order, even if one resource fails to close.
      }
    }
  }

  private static Playwright createPlaywright() {
    try {
      return Playwright.create(
          new Playwright.CreateOptions().setEnv(Map.of("PLAYWRIGHT_SKIP_BROWSER_DOWNLOAD", "1")));
    } catch (PlaywrightException error) {
      throw new SiteAccessException(BROWSER_FAILED, "Could not start Playwright", error);
    }
  }

  private static void closePlaywright(Playwright playwright, RuntimeException failure) {
    try {
      playwright.close();
    } catch (RuntimeException error) {
      failure.addSuppressed(error);
    }
  }
}
