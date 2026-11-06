package com.weavo.ingestion.example;

import java.util.List;
import java.util.Map;

// 예시: 필수 필드에 null이 있는지 확인합니다.
public final class RequiredFieldValidator {
  private RequiredFieldValidator() {}

  public static boolean hasMissingRequiredField(
      Map<String, ?> values, List<String> requiredFields) {
    return requiredFields.stream().anyMatch(field -> values.get(field) == null);
  }
}
