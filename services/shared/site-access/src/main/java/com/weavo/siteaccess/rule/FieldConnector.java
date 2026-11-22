package com.weavo.siteaccess.rule;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Objects;

/** Field-specific extraction and, when required, browser detail-navigation settings. */
public record FieldConnector(
    String fieldId,
    Location location,
    String selector,
    Extract extract,
    Parser parser,
    DetailNavigation detailNavigation) {

  public FieldConnector {
    requireText(fieldId, "fieldId");
    Objects.requireNonNull(location, "location");
    if (location == Location.ABSENT) {
      if (selector != null || detailNavigation != null || parser != null) {
        throw new IllegalArgumentException("ABSENT must not specify selector, detail or parser");
      }
    } else {
      requireText(selector, "selector");
      Objects.requireNonNull(extract, "extract");
      if (location == Location.DETAIL && detailNavigation == null) {
        throw new IllegalArgumentException("DETAIL requires detailNavigation");
      }
      if (location == Location.LIST && detailNavigation != null) {
        throw new IllegalArgumentException("LIST must not specify detailNavigation");
      }
    }
  }

  private static void requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
  }

  public enum Location {
    LIST,
    DETAIL,
    ABSENT
  }

  public enum ExtractType {
    TEXT,
    ATTRIBUTE
  }

  public enum DetailAccessMethod {
    BROWSER
  }

  public enum OpenType {
    LINK,
    CLICK
  }

  public enum Target {
    CURRENT_TAB,
    NEW_TAB,
    MODAL
  }

  public enum ReturnType {
    HISTORY_BACK,
    CLOSE_TAB,
    CLOSE
  }

  public record Extract(ExtractType type, String attribute) {
    public Extract {
      Objects.requireNonNull(type, "type");
      if (type == ExtractType.ATTRIBUTE) {
        requireText(attribute, "attribute");
      } else if (attribute != null) {
        throw new IllegalArgumentException("TEXT must not specify an attribute");
      }
    }
  }

  public record Parser(String code) {
    public Parser {
      requireText(code, "code");
    }
  }

  public record DetailNavigation(
      DetailAccess access, DetailOpen open, @JsonProperty("return") DetailReturn returnRule) {
    public DetailNavigation {
      Objects.requireNonNull(access, "access");
      Objects.requireNonNull(open, "open");
      Objects.requireNonNull(returnRule, "return");
      ReturnType expected =
          switch (open.target()) {
            case CURRENT_TAB -> ReturnType.HISTORY_BACK;
            case NEW_TAB -> ReturnType.CLOSE_TAB;
            case MODAL -> ReturnType.CLOSE;
          };
      if (returnRule.type() != expected) {
        throw new IllegalArgumentException("detail return type does not match the open target");
      }
    }
  }

  public record DetailAccess(DetailAccessMethod method, String waitSelector) {
    public DetailAccess {
      Objects.requireNonNull(method, "method");
      requireText(waitSelector, "waitSelector");
    }
  }

  public record DetailOpen(
      OpenType type,
      String selector,
      @JsonInclude(JsonInclude.Include.NON_NULL) String urlAttribute,
      Target target) {
    public DetailOpen {
      Objects.requireNonNull(type, "type");
      requireText(selector, "selector");
      Objects.requireNonNull(target, "target");
      if (type == OpenType.LINK) {
        requireText(urlAttribute, "urlAttribute");
      }
    }
  }

  public record DetailReturn(
      ReturnType type,
      String waitSelector,
      @JsonInclude(JsonInclude.Include.NON_NULL) String selector) {
    public DetailReturn {
      Objects.requireNonNull(type, "type");
      requireText(waitSelector, "waitSelector");
      if (type == ReturnType.CLOSE) {
        requireText(selector, "selector");
      }
    }
  }
}
