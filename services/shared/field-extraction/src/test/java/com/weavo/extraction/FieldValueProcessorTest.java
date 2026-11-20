package com.weavo.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.weavo.extraction.FieldExtractor.Status;
import com.weavo.extraction.FieldValueConverter.Type;
import com.weavo.extraction.FieldValueProcessor.Stage;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FieldValueProcessorTest {
  private final FieldValueProcessor processor = new FieldValueProcessor(new JavaFieldParser());
  private final HtmlScope document =
      HtmlScope.parse(
          """
          <p class="price">1,200원</p>
          <p class="raw">  원문 &amp; 공백  </p>
          <span class="part">A</span><span class="part">B</span>
          """);

  @Test
  void convertsParsedPriceToNumberAndPreservesRawValues() {
    var extracted = FieldExtractor.extractText(document, ".price");
    var result =
        processor.process(
            extracted,
            """
            return rawValues.getFirst().replace(",", "").replace("원", "");
            """,
            Type.NUMBER,
            true);

    assertThat(result.status()).isEqualTo(Status.SUCCESS);
    assertThat(result.extraction().matchCount()).isEqualTo(1);
    assertThat(result.extraction().rawValues()).containsExactly("1,200원");
    assertThat(result.value()).isEqualTo(new BigDecimal("1.2E+3"));
    assertThat(result.failure()).isNull();
  }

  @Test
  void usesTheOnlyRawStringAsIsWhenThereIsNoParser() {
    var extracted = FieldExtractor.extractText(document, ".raw");
    var result = processor.process(extracted, null, Type.STRING, true);

    assertThat(result.status()).isEqualTo(Status.SUCCESS);
    assertThat(result.value()).isEqualTo("  원문 & 공백  ");
  }

  @Test
  void reportsMultipleRawValuesWithoutAParserAndLetsExplicitCodeCombineThem() {
    var extracted = FieldExtractor.extractText(document, ".part");
    var ambiguous = processor.process(extracted, null, Type.STRING, true);

    assertThat(ambiguous.status()).isEqualTo(Status.FAILED);
    assertThat(ambiguous.failure().stage()).isEqualTo(Stage.PARSER_INPUT);
    assertThat(ambiguous.extraction().rawValues()).isEqualTo(List.of("A", "B"));

    var combined =
        processor.process(extracted, "return String.join(\"/\", rawValues);", Type.STRING, true);
    assertThat(combined.value()).isEqualTo("A/B");
    assertThat(combined.failure()).isNull();
  }

  @Test
  void preservesExtractionFailureEvenWhenTheParserWouldReturnNull() {
    var extracted = FieldExtractor.extractText(document, ".missing");
    var result = processor.process(extracted, "return null;", Type.NUMBER, true);

    assertThat(result.status()).isEqualTo(Status.FAILED);
    assertThat(result.extraction().matchCount()).isZero();
    assertThat(result.failure().stage()).isEqualTo(Stage.EXTRACTION);
    assertThat(result.failure().reason()).isEqualTo("NO_MATCH");
  }

  @Test
  void keepsAnAbsentFieldAndASuccessfulNullDistinct() {
    var absent = processor.process(FieldExtractor.absent(), "not valid Java", Type.NUMBER, true);
    var extracted = FieldExtractor.extractText(document, ".price");
    var parsedNull = processor.process(extracted, "return null;", Type.NUMBER, true);

    assertThat(absent.status()).isEqualTo(Status.ABSENT);
    assertThat(absent.value()).isNull();
    assertThat(absent.failure()).isNull();
    assertThat(parsedNull.status()).isEqualTo(Status.SUCCESS);
    assertThat(parsedNull.value()).isNull();
    assertThat(parsedNull.failure()).isNull();

    var rejected = processor.process(extracted, "return null;", Type.NUMBER, false);
    assertThat(rejected.failure().stage()).isEqualTo(Stage.TYPE_CONVERSION);
    assertThat(rejected.failure().reason()).isEqualTo("NULL_NOT_ALLOWED");
  }

  @ParameterizedTest
  @ValueSource(strings = {"AssertionError", "InterruptedException"})
  void keepsProcessingAfterAParserFailure(String exceptionName) {
    var extracted = FieldExtractor.extractText(document, ".price");
    var failed =
        processor.process(extracted, "throw new " + exceptionName + "();", Type.STRING, true);

    assertThat(failed.status()).isEqualTo(Status.FAILED);
    assertThat(failed.value()).isNull();
    assertThat(failed.failure().stage()).isEqualTo(Stage.PARSER);
    assertThat(failed.failure().parserFailure().stage()).isEqualTo(JavaFieldParser.Stage.EXECUTION);
    assertThat(failed.failure().parserFailure().exceptionType())
        .isEqualTo("java.lang." + exceptionName);
    assertThat(Thread.currentThread().isInterrupted()).isFalse();

    var next = processor.process(extracted, "return rawValues.getFirst();", Type.STRING, true);
    assertThat(next.status()).isEqualTo(Status.SUCCESS);
    assertThat(next.value()).isEqualTo("1,200원");
  }

  @Test
  void distinguishesCompilationExecutionAndTypeConversionFailures() {
    var extracted = FieldExtractor.extractText(document, ".price");
    var compile = processor.process(extracted, "return 42;", Type.NUMBER, true);
    var execute = processor.process(extracted, "return rawValues.get(5);", Type.NUMBER, true);
    var convert = processor.process(extracted, "return rawValues.getFirst();", Type.NUMBER, true);

    assertThat(compile.status()).isEqualTo(Status.FAILED);
    assertThat(compile.failure().stage()).isEqualTo(Stage.PARSER);
    assertThat(compile.failure().parserFailure().stage())
        .isEqualTo(JavaFieldParser.Stage.COMPILATION);
    assertThat(execute.failure().stage()).isEqualTo(Stage.PARSER);
    assertThat(execute.failure().parserFailure().stage())
        .isEqualTo(JavaFieldParser.Stage.EXECUTION);
    assertThat(convert.failure().stage()).isEqualTo(Stage.TYPE_CONVERSION);
    assertThat(convert.value()).isNull();
  }
}
