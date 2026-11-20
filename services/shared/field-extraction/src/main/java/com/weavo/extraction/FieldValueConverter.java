package com.weavo.extraction;

import java.math.BigDecimal;
import java.net.URI;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.regex.Pattern;

/** Converts one extracted or parsed value to the scalar representation in the data contract. */
public final class FieldValueConverter {
  private static final Pattern JSON_NUMBER =
      Pattern.compile("-?(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?(?:[eE][+-]?[0-9]+)?");
  private static final Pattern DATE = Pattern.compile("[0-9]{4}-[0-9]{2}-[0-9]{2}");
  private static final Pattern DATETIME =
      Pattern.compile(
          "[0-9]{4}-[0-9]{2}-[0-9]{2}[Tt][0-9]{2}:[0-9]{2}:[0-9]{2}"
              + "(?:\\.[0-9]{1,9})?(?:[Zz]|[+-][0-9]{2}:[0-9]{2})");

  private FieldValueConverter() {}

  public static Result convert(String raw, Type type, boolean nullable) {
    Objects.requireNonNull(type, "type");
    if (raw == null) {
      return nullable ? new Result(null, null) : new Result(null, Failure.NULL_NOT_ALLOWED);
    }
    try {
      Object value =
          switch (type) {
            case STRING -> raw;
            case NUMBER -> toNumber(raw);
            case BOOLEAN -> toBoolean(raw);
            case DATE -> toDate(raw);
            case DATETIME -> toUtcDateTime(raw);
            case URL -> validateUrl(raw);
          };
      return new Result(value, null);
    } catch (IllegalArgumentException | ArithmeticException | DateTimeException exception) {
      return new Result(null, Failure.TYPE_CONVERSION_FAILED);
    }
  }

  private static BigDecimal toNumber(String raw) {
    if (!JSON_NUMBER.matcher(raw).matches()) {
      throw new IllegalArgumentException("Expected a JSON number");
    }
    return new BigDecimal(raw).stripTrailingZeros();
  }

  private static boolean toBoolean(String raw) {
    return switch (raw) {
      case "true" -> true;
      case "false" -> false;
      default -> throw new IllegalArgumentException("Expected true or false");
    };
  }

  private static String toDate(String raw) {
    if (!DATE.matcher(raw).matches()) {
      throw new IllegalArgumentException("Expected YYYY-MM-DD");
    }
    return LocalDate.parse(raw).toString();
  }

  private static String toUtcDateTime(String raw) {
    if (!DATETIME.matcher(raw).matches()) {
      throw new IllegalArgumentException("Expected an offset date-time with seconds");
    }
    var utc = OffsetDateTime.parse(raw).withOffsetSameInstant(ZoneOffset.UTC);
    if (utc.getYear() < 0 || utc.getYear() > 9999) {
      throw new IllegalArgumentException("UTC year is outside the contract format");
    }
    return utc.toInstant().toString();
  }

  private static String validateUrl(String raw) {
    URI uri = URI.create(raw);
    if (uri.getHost() == null
        || !("http".equalsIgnoreCase(uri.getScheme())
            || "https".equalsIgnoreCase(uri.getScheme()))) {
      throw new IllegalArgumentException("Expected an absolute HTTP(S) URL");
    }
    return raw;
  }

  public enum Type {
    STRING,
    NUMBER,
    BOOLEAN,
    DATE,
    DATETIME,
    URL
  }

  public enum Failure {
    NULL_NOT_ALLOWED,
    TYPE_CONVERSION_FAILED
  }

  public record Result(Object value, Failure failure) {}
}
