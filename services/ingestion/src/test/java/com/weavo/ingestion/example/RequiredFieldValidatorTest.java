package com.weavo.ingestion.example;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RequiredFieldValidatorTest {
  @Test
  void detectsMissingRequiredValues() {
    assertTrue(RequiredFieldValidator.hasMissingRequiredField(Map.of(), List.of("title")));
    assertFalse(
        RequiredFieldValidator.hasMissingRequiredField(Map.of("title", "공지"), List.of("title")));
  }
}
