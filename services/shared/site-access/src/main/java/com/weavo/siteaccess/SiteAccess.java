package com.weavo.siteaccess;

import java.net.URI;
import java.util.Objects;
import okhttp3.Dns;

/** Entry point shared by Analysis and Crawler for public HTTP and Chromium access. */
public final class SiteAccess {
  static final String PRODUCT_TOKEN = "weavobot";
  static final String USER_AGENT = "WeavoBot/1.0";
  private final HttpTransport transport;

  public SiteAccess(RequestCoordinator coordinator) {
    this(coordinator, host -> PublicUrls.resolvePublic(host, Dns.SYSTEM));
  }

  SiteAccess(RequestCoordinator coordinator, Dns dns) {
    this.transport = new HttpTransport(Objects.requireNonNull(coordinator), dns);
  }

  /** Uses the same URL rules as an actual navigation without resolving DNS or sending a request. */
  public static URI normalizeAddress(String address) {
    return PublicUrls.parse(address).uri();
  }

  public SiteSession open(WorkBudget budget) {
    return new SiteSession(transport, Objects.requireNonNull(budget));
  }

  public record PageSnapshot(URI url, String html) {
    @Override
    public String toString() {
      return "PageSnapshot";
    }
  }
}
