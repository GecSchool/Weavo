package com.weavo.analysis.source.application;

import com.weavo.extraction.FieldValueConverter.Type;
import com.weavo.siteaccess.rule.FieldConnector;
import com.weavo.siteaccess.rule.FieldConnector.Location;
import com.weavo.siteaccess.rule.Navigation;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** One source's provisional fields, keyed independently of the final project schema. */
public record SourceCandidate(Navigation navigation, List<Field> fields) {
  public SourceCandidate {
    Objects.requireNonNull(navigation, "navigation");
    fields = List.copyOf(fields);
    var keys = new HashSet<String>();
    if (fields.isEmpty()
        || fields.stream().allMatch(field -> field.connector().location() == Location.ABSENT)) {
      throw new IllegalArgumentException("At least one field must be present on the source");
    }
    for (Field field : fields) {
      if (!keys.add(field.connector().fieldId())) {
        throw new IllegalArgumentException("Candidate keys must be unique within the source");
      }
    }
  }

  public record Field(
      String name, Type type, boolean nullable, String reason, FieldConnector connector) {
    public Field {
      if (name == null || name.isBlank() || reason == null || reason.isBlank()) {
        throw new IllegalArgumentException("Candidate field name and reason are required");
      }
      Objects.requireNonNull(type, "type");
      Objects.requireNonNull(connector, "connector");
      if (connector.location() == Location.ABSENT && (connector.extract() != null || !nullable)) {
        throw new IllegalArgumentException(
            "An absent field must be nullable and have no extract rule");
      }
    }
  }
}
