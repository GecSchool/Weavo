package com.weavo.siteaccess;

/** Describes an access failure without exposing response content or request credentials. */
public final class SiteAccessException extends RuntimeException {
  public enum Kind {
    INVALID_URL,
    ACCESS_BLOCKED,
    ROBOTS_DENIED,
    REQUEST_COORDINATION_FAILED,
    SITE_UNAVAILABLE,
    HTTP_STATUS,
    HTTP_TIMEOUT,
    BROWSER_TIMEOUT,
    BROWSER_FAILED,
    WORK_LIMIT,
    CANCELLED,
    REDIRECT_LIMIT,
    RESPONSE_TOO_LARGE
  }

  private final Kind kind;
  private final Integer status;

  public SiteAccessException(Kind kind, String message) {
    this(kind, message, null, null);
  }

  SiteAccessException(Kind kind, String message, Throwable cause) {
    this(kind, message, null, cause);
  }

  SiteAccessException(Kind kind, String message, Integer status, Throwable cause) {
    super(message, cause);
    this.kind = kind;
    this.status = status;
  }

  public Kind kind() {
    return kind;
  }

  public Integer status() {
    return status;
  }
}
