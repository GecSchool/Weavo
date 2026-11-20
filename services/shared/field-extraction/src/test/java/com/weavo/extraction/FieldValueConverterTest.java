package com.weavo.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.weavo.extraction.FieldValueConverter.Failure;
import com.weavo.extraction.FieldValueConverter.Result;
import com.weavo.extraction.FieldValueConverter.Type;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FieldValueConverterTest {
  @ParameterizedTest
  @ValueSource(strings = {"", "  공지  ", "미정", "없음"})
  void preservesTheRawString(String raw) {
    assertThat(FieldValueConverter.convert(raw, Type.STRING, true))
        .isEqualTo(new Result(raw, null));
  }

  @Test
  void convertsDecimalsWithoutLosingPrecisionOrDependingOnTheirScale() {
    String raw = "123456789012345678901234567890.1234567890123456789";
    assertThat(FieldValueConverter.convert(raw, Type.NUMBER, false).value())
        .isEqualTo(new BigDecimal(raw));
    assertThat(FieldValueConverter.convert("1.00", Type.NUMBER, false))
        .isEqualTo(FieldValueConverter.convert("1e0", Type.NUMBER, false));
    assertThat(FieldValueConverter.convert("-0.0", Type.NUMBER, false).value())
        .isEqualTo(BigDecimal.ZERO);
  }

  @Test
  void convertsExplicitBooleanValuesAndRejectsUnrecognizedStrings() {
    assertThat(FieldValueConverter.convert("true", Type.BOOLEAN, false))
        .isEqualTo(new Result(true, null));
    assertThat(FieldValueConverter.convert("false", Type.BOOLEAN, false))
        .isEqualTo(new Result(false, null));
    assertThat(FieldValueConverter.convert("yes", Type.BOOLEAN, true).failure())
        .isEqualTo(Failure.TYPE_CONVERSION_FAILED);
  }

  @Test
  void validatesCalendarDatesAndCanonicalizesOffsetDateTimesToUtc() {
    assertThat(FieldValueConverter.convert("2024-02-29", Type.DATE, false).value())
        .isEqualTo("2024-02-29");
    assertThat(FieldValueConverter.convert("2026-02-29", Type.DATE, true).failure())
        .isEqualTo(Failure.TYPE_CONVERSION_FAILED);
    var result =
        FieldValueConverter.convert("2026-11-20T09:05:01.123456789+09:00", Type.DATETIME, false);
    assertThat(result).isEqualTo(new Result("2026-11-20T00:05:01.123456789Z", null));
    assertThat(FieldValueConverter.convert("2026-11-20T09:05", Type.DATETIME, true).failure())
        .isEqualTo(Failure.TYPE_CONVERSION_FAILED);
    assertThat(
            FieldValueConverter.convert("2026-11-20T09:05:00.1234567891Z", Type.DATETIME, true)
                .failure())
        .isEqualTo(Failure.TYPE_CONVERSION_FAILED);
  }

  @Test
  void validatesAbsoluteHttpUrlsAndPreservesTheirRawRepresentation() {
    String raw = "HTTPS://Example.com/notice?b=%2F&a=1#section";
    assertThat(FieldValueConverter.convert(raw, Type.URL, false)).isEqualTo(new Result(raw, null));
    assertThat(FieldValueConverter.convert("/notice?x=1", Type.URL, true).failure())
        .isEqualTo(Failure.TYPE_CONVERSION_FAILED);
    assertThat(FieldValueConverter.convert("ftp://example.com/notice", Type.URL, true).failure())
        .isEqualTo(Failure.TYPE_CONVERSION_FAILED);
  }

  @ParameterizedTest
  @ValueSource(strings = {"", "미정", "NaN", "Infinity", " 1", "1 ", "1,000", "01"})
  void rejectsInvalidNumbersEvenWhenTheFieldIsNullable(String raw) {
    assertThat(FieldValueConverter.convert(raw, Type.NUMBER, true))
        .isEqualTo(new Result(null, Failure.TYPE_CONVERSION_FAILED));
  }

  @Test
  void keepsAnActualNullSeparateFromAConversionOrNullabilityFailure() {
    assertThat(FieldValueConverter.convert(null, Type.NUMBER, true))
        .isEqualTo(new Result(null, null));
    assertThat(FieldValueConverter.convert(null, Type.NUMBER, false))
        .isEqualTo(new Result(null, Failure.NULL_NOT_ALLOWED));
  }
}
