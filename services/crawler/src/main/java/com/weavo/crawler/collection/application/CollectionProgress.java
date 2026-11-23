package com.weavo.crawler.collection.application;

import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionProject;
import com.weavo.extraction.FieldValueConverter;
import com.weavo.extraction.FieldValueConverter.Type;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Counts distinct observations against the stored occurrences for each project source. */
final class CollectionProgress {
  private final Map<String, Project> projects = new LinkedHashMap<>();

  CollectionProgress(List<CollectionProject> targets) {
    for (CollectionProject target : targets) {
      projects.put(target.projectSourceId(), new Project(target));
    }
  }

  boolean participates(String projectSourceId) {
    return !projects.get(projectSourceId).finished;
  }

  void observe(Map<String, Map<String, Object>> values) {
    values.forEach((id, fields) -> projects.get(id).observe(fields));
  }

  boolean endSegment() {
    projects.values().forEach(Project::endSegment);
    return projects.values().stream().allMatch(project -> project.finished);
  }

  long count(String projectSourceId) {
    return projects.get(projectSourceId).count;
  }

  boolean hasCandidates(String projectSourceId) {
    return projects.get(projectSourceId).hasCandidates;
  }

  boolean hasCandidates() {
    return projects.values().stream().anyMatch(project -> project.hasCandidates);
  }

  private static Map<String, Object> normalizeDatetimes(
      Map<String, Object> values, Set<String> datetimeFields) {
    var normalized = new LinkedHashMap<>(values);
    for (String id : datetimeFields) {
      if (normalized.get(id) instanceof String text) {
        var result = FieldValueConverter.convert(text, Type.DATETIME, true);
        if (result.failure() == null) {
          normalized.put(id, result.value());
        }
      }
    }
    return normalized;
  }

  private static boolean sameValues(Map<String, Object> left, Map<String, Object> right) {
    return left.keySet().equals(right.keySet())
        && left.entrySet().stream()
            .allMatch(entry -> sameValue(entry.getValue(), right.get(entry.getKey())));
  }

  private static boolean sameValue(Object left, Object right) {
    if (left instanceof Number a && right instanceof Number b) {
      return new BigDecimal(a.toString()).compareTo(new BigDecimal(b.toString())) == 0;
    }
    return Objects.equals(left, right);
  }

  private static final class Project {
    private final List<Map<String, Object>> latest;
    private final boolean[] matched;
    private int matchCount;
    private boolean extraSegment;
    private boolean finished;
    private boolean hasCandidates;
    private long count;

    private Project(CollectionProject target) {
      Set<String> datetimeFields =
          target.schema().fields().stream()
              .filter(field -> "datetime".equals(field.type()))
              .map(field -> field.fieldId())
              .collect(Collectors.toSet());
      latest =
          target.latestRecords().stream()
              .map(record -> normalizeDatetimes(record.fieldValues(), datetimeFields))
              .toList();
      matched = new boolean[latest.size()];
    }

    private void observe(Map<String, Object> values) {
      count++;
      boolean known = false;
      boolean used = false;
      for (int i = 0; i < latest.size(); i++) {
        if (!sameValues(values, latest.get(i))) {
          continue;
        }
        known = true;
        if (!used && !matched[i]) {
          matched[i] = true;
          matchCount++;
          used = true;
        }
      }
      hasCandidates |= !known;
    }

    private void endSegment() {
      if (finished || latest.isEmpty()) {
        return;
      }
      if (extraSegment || matchCount == latest.size()) {
        finished = true;
      } else if (latest.size() == 3 && matchCount == 2) {
        extraSegment = true;
      }
    }
  }
}
