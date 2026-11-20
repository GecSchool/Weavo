package com.weavo.extraction;

import static org.assertj.core.api.Assertions.assertThat;

import com.weavo.extraction.FieldExtractor.Failure;
import com.weavo.extraction.FieldExtractor.Result;
import com.weavo.extraction.FieldExtractor.Status;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class FieldExtractorTest {
  private final HtmlScope document = HtmlScope.parse(fixture());

  FieldExtractorTest() throws IOException {}

  @Test
  void extractsScopedTextInDomOrderWithoutTrimmingOrInsertingWhitespace() {
    var result = FieldExtractor.extractText(document, "article.notice > p.title");

    assertThat(result)
        .isEqualTo(new Result(Status.SUCCESS, 2, List.of("  새 & 공지둘째\u00a0줄  ", "다음 공지"), null));
    var first = document.select("article.notice").getFirst();
    assertThat(FieldExtractor.extractText(first, ".title").rawValues())
        .containsExactly("  새 & 공지둘째\u00a0줄  ");
    assertThat(FieldExtractor.extractText(first, ".notice").matchCount()).isZero();
    assertThat(FieldExtractor.extractText(first, ".empty").rawValues()).containsExactly("");
  }

  @Test
  void keepsEmptyAndRelativeAttributeValuesAsWrittenInTheDom() {
    var first = document.select("#first").getFirst();
    var result = FieldExtractor.extractAttribute(first, "a.link", "href");

    assertThat(result)
        .isEqualTo(new Result(Status.SUCCESS, 2, List.of("/notices/1?a=1&b=2", ""), null));
    assertThat(FieldExtractor.extractAttribute(first, ".literal", "abs:href").rawValues())
        .containsExactly("literal");
  }

  @Test
  void recordsAllMatchesAndAvailableRawValuesWhenAnAttributeIsMissing() {
    var first = document.select("#first").getFirst();
    var result = FieldExtractor.extractAttribute(first, "a.link, a.missing", "href");

    assertThat(result)
        .isEqualTo(
            new Result(
                Status.FAILED, 3, List.of("/notices/1?a=1&b=2", ""), Failure.ATTRIBUTE_MISSING));
  }

  @Test
  void distinguishesAbsentUnexecutedInvalidAndUnmatchedSelectors() {
    assertThat(FieldExtractor.absent()).isEqualTo(new Result(Status.ABSENT, null, List.of(), null));
    assertThat(FieldExtractor.extractText(null, ".title"))
        .isEqualTo(new Result(Status.FAILED, null, List.of(), Failure.SELECTOR_NOT_RUN));
    assertThat(FieldExtractor.extractText(document, "a["))
        .isEqualTo(new Result(Status.FAILED, null, List.of(), Failure.INVALID_SELECTOR));
    assertThat(FieldExtractor.extractText(document, ".unmatched"))
        .isEqualTo(new Result(Status.FAILED, 0, List.of(), Failure.NO_MATCH));
  }

  private static String fixture() throws IOException {
    try (var input = FieldExtractorTest.class.getResourceAsStream("/field-extraction.html")) {
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    }
  }
}
