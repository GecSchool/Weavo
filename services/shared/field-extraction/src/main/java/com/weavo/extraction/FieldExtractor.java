package com.weavo.extraction;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jsoup.select.Selector.SelectorParseException;

/** Runs the selector and captures DOM strings before parsing or type conversion. */
public final class FieldExtractor {
  private FieldExtractor() {}

  public static Result absent() {
    return new Result(Status.ABSENT, null, List.of(), null);
  }

  public static Result extractText(HtmlScope scope, String selector) {
    return extract(scope, selector, HtmlScope::textContent);
  }

  public static Result extractAttribute(HtmlScope scope, String selector, String attribute) {
    if (attribute == null || attribute.isBlank()) {
      return failed(Failure.ATTRIBUTE_NOT_CONFIGURED);
    }
    return extract(scope, selector, element -> element.attribute(attribute));
  }

  private static Result extract(
      HtmlScope scope, String selector, Function<HtmlScope, String> readValue) {
    if (scope == null) {
      return failed(Failure.SELECTOR_NOT_RUN);
    }
    if (selector == null || selector.isBlank()) {
      return failed(Failure.INVALID_SELECTOR);
    }

    List<HtmlScope> matches;
    try {
      matches = scope.select(selector);
    } catch (SelectorParseException exception) {
      return failed(Failure.INVALID_SELECTOR);
    }
    if (matches.isEmpty()) {
      return new Result(Status.FAILED, 0, List.of(), Failure.NO_MATCH);
    }

    var rawValues = new ArrayList<String>(matches.size());
    for (HtmlScope match : matches) {
      String value = readValue.apply(match);
      if (value != null) {
        rawValues.add(value);
      }
    }
    if (rawValues.size() != matches.size()) {
      return new Result(Status.FAILED, matches.size(), rawValues, Failure.ATTRIBUTE_MISSING);
    }
    return new Result(Status.SUCCESS, matches.size(), rawValues, null);
  }

  private static Result failed(Failure failure) {
    return new Result(Status.FAILED, null, List.of(), failure);
  }

  public enum Status {
    SUCCESS,
    ABSENT,
    FAILED
  }

  public enum Failure {
    SELECTOR_NOT_RUN,
    INVALID_SELECTOR,
    NO_MATCH,
    ATTRIBUTE_NOT_CONFIGURED,
    ATTRIBUTE_MISSING
  }

  /** Available failed raw values are for sidecar diagnostics, never completed record values. */
  public record Result(Status status, Integer matchCount, List<String> rawValues, Failure failure) {
    public Result {
      rawValues = List.copyOf(rawValues);
    }
  }
}
