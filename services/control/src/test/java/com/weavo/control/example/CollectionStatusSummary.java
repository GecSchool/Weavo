package com.weavo.control.example;

import java.util.List;

// 예시: 프로젝트 화면에 전달할 출처 상태별 개수를 계산합니다.
public final class CollectionStatusSummary {
  private CollectionStatusSummary() {}

  public static long countPending(List<SourceStatus> statuses) {
    return statuses.stream().filter(status -> status == SourceStatus.PENDING).count();
  }

  public enum SourceStatus {
    PENDING,
    ACTIVE,
    NEEDS_ATTENTION
  }
}
