package com.weavo.control.example;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class CollectionStatusSummaryTest {
  @Test
  void countsPendingSources() {
    var statuses =
        List.of(
            CollectionStatusSummary.SourceStatus.PENDING,
            CollectionStatusSummary.SourceStatus.ACTIVE,
            CollectionStatusSummary.SourceStatus.PENDING);
    assertEquals(2, CollectionStatusSummary.countPending(statuses));
  }
}
