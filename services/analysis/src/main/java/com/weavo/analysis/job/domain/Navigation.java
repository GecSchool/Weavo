package com.weavo.analysis.job.domain;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.net.URI;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** The complete list-navigation rule shared by analysis and collection commands. */
public record Navigation(
    String listUrl,
    List<InitialAction> initialActions,
    ListAccess listAccess,
    String itemSelector,
    Pagination pagination) {

  public Navigation {
    requireHttpUrl(listUrl, "listUrl");
    initialActions = List.copyOf(Objects.requireNonNull(initialActions, "initialActions"));
    Objects.requireNonNull(listAccess, "listAccess");
    requireText(itemSelector, "itemSelector");
    Objects.requireNonNull(pagination, "pagination");
    if (listAccess.method() == AccessMethod.HTTP) {
      if (!initialActions.isEmpty()) {
        throw new IllegalArgumentException("HTTP list access does not support initial actions");
      }
      if (pagination.type() != PaginationType.NONE
          && pagination.type() != PaginationType.NEXT_LINK
          && pagination.type() != PaginationType.URL_TEMPLATE) {
        throw new IllegalArgumentException(
            "HTTP list access does not support this pagination type");
      }
    }
  }

  /** Compares the complete list rules without discarding meaningful path or query information. */
  public boolean hasSameSourceAs(Navigation other) {
    return other != null
        && normalizedListUrl(listUrl).equals(normalizedListUrl(other.listUrl))
        && initialActions.equals(other.initialActions)
        && listAccess.equals(other.listAccess)
        && itemSelector.equals(other.itemSelector)
        && pagination.equals(other.pagination);
  }

  /** Normalizes only scheme, host, default port and fragment, preserving the raw path and query. */
  public static String normalizedListUrl(String url) {
    URI uri = requireHttpUrl(url, "listUrl");
    String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
    String host = uri.getHost().toLowerCase(Locale.ROOT);
    StringBuilder value = new StringBuilder(scheme).append("://");
    if (uri.getRawUserInfo() != null) {
      value.append(uri.getRawUserInfo()).append('@');
    }
    if (host.indexOf(':') >= 0 && !host.startsWith("[")) {
      value.append('[').append(host).append(']');
    } else {
      value.append(host);
    }
    int port = uri.getPort();
    if (port != -1
        && !(scheme.equals("http") && port == 80)
        && !(scheme.equals("https") && port == 443)) {
      value.append(':').append(port);
    }
    if (uri.getRawPath() != null) {
      value.append(uri.getRawPath());
    }
    if (uri.getRawQuery() != null) {
      value.append('?').append(uri.getRawQuery());
    }
    return value.toString();
  }

  private static URI requireHttpUrl(String value, String name) {
    requireText(value, name);
    final URI uri;
    try {
      uri = URI.create(value);
    } catch (IllegalArgumentException exception) {
      // URI parser exceptions can contain the full URL, including confidential query strings.
      throw new IllegalArgumentException(name + " must be an absolute HTTP(S) URL");
    }
    if (!uri.isAbsolute()
        || uri.getHost() == null
        || !("http".equalsIgnoreCase(uri.getScheme())
            || "https".equalsIgnoreCase(uri.getScheme()))) {
      throw new IllegalArgumentException(name + " must be an absolute HTTP(S) URL with a host");
    }
    return uri;
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }

  public enum AccessMethod {
    HTTP,
    BROWSER
  }

  public enum ActionType {
    CLICK
  }

  public enum PaginationType {
    NONE,
    NEXT_LINK,
    URL_TEMPLATE,
    CLICK_NEXT,
    LOAD_MORE,
    SCROLL
  }

  public record InitialAction(ActionType type, String selector, String waitSelector) {
    public InitialAction {
      Objects.requireNonNull(type, "type");
      requireText(selector, "selector");
      requireText(waitSelector, "waitSelector");
    }
  }

  public record ListAccess(AccessMethod method, String waitSelector) {
    public ListAccess {
      Objects.requireNonNull(method, "method");
    }
  }

  /** Optional properties remain null for pagination types which do not use them. */
  public record Pagination(
      PaginationType type,
      String selector,
      String urlAttribute,
      String waitSelector,
      @JsonInclude(JsonInclude.Include.NON_NULL) String urlTemplate,
      @JsonInclude(JsonInclude.Include.NON_NULL) Integer start,
      @JsonInclude(JsonInclude.Include.NON_NULL) Integer step,
      String scrollContainerSelector) {
    public Pagination {
      Objects.requireNonNull(type, "type");
      switch (type) {
        case NONE -> {}
        case NEXT_LINK -> {
          requireText(selector, "selector");
          requireText(urlAttribute, "urlAttribute");
        }
        case URL_TEMPLATE -> {
          requireText(urlTemplate, "urlTemplate");
          int first = urlTemplate.indexOf("{value}");
          if (first < 0 || first != urlTemplate.lastIndexOf("{value}")) {
            throw new IllegalArgumentException("urlTemplate must contain {value} exactly once");
          }
          requireHttpUrl(urlTemplate.replace("{value}", "0"), "urlTemplate");
          Objects.requireNonNull(start, "start");
          if (step == null || step <= 0) {
            throw new IllegalArgumentException("step must be a positive integer");
          }
        }
        case CLICK_NEXT, LOAD_MORE -> {
          requireText(selector, "selector");
          requireText(waitSelector, "waitSelector");
        }
        case SCROLL -> {
          requireText(waitSelector, "waitSelector");
          if (scrollContainerSelector != null && scrollContainerSelector.isBlank()) {
            throw new IllegalArgumentException("scrollContainerSelector must be null or nonblank");
          }
        }
      }
    }
  }
}
