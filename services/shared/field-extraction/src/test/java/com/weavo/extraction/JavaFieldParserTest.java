package com.weavo.extraction;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import com.weavo.extraction.JavaFieldParser.Result;
import com.weavo.extraction.JavaFieldParser.Stage;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JavaFieldParserTest {
  private final JavaFieldParser parser = new JavaFieldParser();

  @Test
  void evaluatesTheSameBodyAndInputIndependentlyAcrossServiceInstances() {
    String code =
        """
        rawValues.sort(java.util.Comparator.naturalOrder());
        return String.join(" / ", rawValues);
        """;
    var rawValues = List.of("C", "A", "B");

    for (int service = 0; service < 3; service++) {
      assertThat(new JavaFieldParser().parse(code, rawValues))
          .isEqualTo(new Result("A / B / C", null));
    }
    assertThat(rawValues).containsExactly("C", "A", "B");
    assertThat(parser.parse(code, List.of("other"))).isEqualTo(new Result("other", null));
    assertThat(parser.parse(code, List.of("new input"))).isEqualTo(new Result("new input", null));
  }

  @Test
  void keepsAnIntentionalNullSeparateFromARuntimeFailure() {
    assertThat(parser.parse("return null;", List.of("미정"))).isEqualTo(new Result(null, null));

    var result =
        parser.parse(
            "throw new java.io.IOException(rawValues.getFirst());", List.of("private fixture"));
    assertThat(result.value()).isNull();
    assertThat(result.failure().stage()).isEqualTo(Stage.EXECUTION);
    assertThat(result.failure().exceptionType()).isEqualTo("java.io.IOException");
    assertThat(result.failure().toString()).doesNotContain("private fixture");
  }

  @Test
  void preservesTheCompilerErrorPositionRelativeToTheSubmittedBody() {
    var failure =
        parser
            .parse("String formatted = rawValues.getFirst();\nreturn 42;", List.of("value"))
            .failure();

    assertThat(failure.stage()).isEqualTo(Stage.COMPILATION);
    assertThat(failure.diagnostics())
        .singleElement()
        .satisfies(diagnostic -> {
          assertThat(diagnostic.code()).startsWith("compiler.err.");
          assertThat(diagnostic.line()).isEqualTo(2);
          assertThat(diagnostic.column()).isPositive();
        });
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "",
        "return ;",
        "import java.time.LocalDate; return null;",
        "class Unrelated {} return null;",
        "return null; } public static String unrelated() { return null;"
      })
  void rejectsAnInvalidBodyOrAnAdditionalDeclaration(String code) {
    var result = parser.parse(code, List.of("value"));

    assertThat(result.value()).isNull();
    assertThat(result.failure().stage()).isEqualTo(Stage.COMPILATION);
    assertThat(result.failure().diagnostics()).isNotEmpty();
  }

  @Test
  void propagatesAVirtualMachineError() {
    assertThatExceptionOfType(InternalError.class)
        .isThrownBy(() -> parser.parse("throw new InternalError();", List.of("value")));
  }
}
