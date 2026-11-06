package com.weavo.data.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RecordFieldSelectorTest {
  @Test
  void selectsOnlyRequestedExistingFields() {
    var record = Map.<String, Object>of("title", "공지", "date", "2026-11-07");
    assertEquals(Map.of("title", "공지"), RecordFieldSelector.select(record, List.of("title")));
  }
}
