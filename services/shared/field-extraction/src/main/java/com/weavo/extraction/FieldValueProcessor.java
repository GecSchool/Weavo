package com.weavo.extraction;

import com.weavo.extraction.FieldExtractor.Status;
import com.weavo.extraction.FieldValueConverter.Type;
import java.util.List;
import java.util.Objects;

/** Resolves an extraction result to one typed field value and keeps the stage that failed. */
public final class FieldValueProcessor {
  private final JavaFieldParser parser;

  public FieldValueProcessor(JavaFieldParser parser) {
    this.parser = Objects.requireNonNull(parser, "parser");
  }

  public Result process(
      FieldExtractor.Result extraction, String parserCode, Type type, boolean nullable) {
    if (extraction.status() == Status.FAILED) {
      return failed(extraction, new Failure(Stage.EXTRACTION, extraction.failure().name(), null));
    }

    String raw = null;
    if (extraction.status() == Status.SUCCESS) {
      List<String> candidates = extraction.rawValues();
      if (parserCode == null) {
        if (candidates.size() != 1) {
          return failed(extraction, new Failure(Stage.PARSER_INPUT, "SINGLE_VALUE_REQUIRED", null));
        }
        raw = candidates.getFirst();
      } else {
        var parsed = parser.parse(parserCode, candidates);
        if (parsed.failure() != null) {
          return failed(
              extraction,
              new Failure(Stage.PARSER, parsed.failure().stage().name(), parsed.failure()));
        }
        raw = parsed.value();
      }
    }

    var converted = FieldValueConverter.convert(raw, type, nullable);
    if (converted.failure() != null) {
      return failed(
          extraction, new Failure(Stage.TYPE_CONVERSION, converted.failure().name(), null));
    }
    return new Result(extraction.status(), extraction, converted.value(), null);
  }

  private static Result failed(FieldExtractor.Result extraction, Failure failure) {
    return new Result(Status.FAILED, extraction, null, failure);
  }

  public enum Stage {
    EXTRACTION,
    PARSER_INPUT,
    PARSER,
    TYPE_CONVERSION
  }

  public record Failure(Stage stage, String reason, JavaFieldParser.Failure parserFailure) {}

  public record Result(
      Status status, FieldExtractor.Result extraction, Object value, Failure failure) {}
}
