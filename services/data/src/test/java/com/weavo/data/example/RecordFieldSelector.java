package com.weavo.data.example;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

// 예시: 요청한 필드만 응답용 값으로 선택합니다.
public final class RecordFieldSelector {
  private RecordFieldSelector() {}

  public static Map<String, Object> select(Map<String, Object> record, List<String> fields) {
    Map<String, Object> selected = new LinkedHashMap<>();
    for (String field : fields) {
      if (record.containsKey(field)) {
        selected.put(field, record.get(field));
      }
    }
    return selected;
  }
}
