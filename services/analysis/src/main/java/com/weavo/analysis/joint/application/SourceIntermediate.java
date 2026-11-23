package com.weavo.analysis.joint.application;

import com.weavo.extraction.FieldValueConverter.Type;
import com.weavo.siteaccess.rule.FieldConnector;
import com.weavo.siteaccess.rule.FieldConnector.DetailNavigation;
import com.weavo.siteaccess.rule.FieldConnector.Extract;
import com.weavo.siteaccess.rule.FieldConnector.Location;
import com.weavo.siteaccess.rule.FieldConnector.Parser;
import com.weavo.siteaccess.rule.Navigation;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public record SourceIntermediate(
    int formatVersion,
    String analysisId,
    String jobId,
    String projectId,
    String projectSourceId,
    String listUrl,
    String createdAt,
    List<Field> fields,
    Navigation navigation,
    List<DetailNavigation> detailCandidates,
    String validationStatus) {
  public int sampleCount() {
    if (fields == null || fields.isEmpty()) {
      throw new IllegalArgumentException("Intermediate result has no fields");
    }
    int count = fields.getFirst().sampleValues().size();
    if (count == 0
        || count > 50
        || fields.stream().anyMatch(f -> f.sampleValues().size() != count)) {
      throw new IllegalArgumentException("Intermediate fields have inconsistent samples");
    }
    return count;
  }

  public record Field(
      String candidateKey,
      String name,
      String suggestedType,
      List<Object> sampleValues,
      Location location,
      String selector,
      Extract extract,
      DetailNavigation detailNavigation,
      Parser parser,
      String reason,
      String validationStatus) {
    public Field {
      sampleValues = Collections.unmodifiableList(new ArrayList<>(sampleValues));
    }

    Type type() {
      return Type.valueOf(suggestedType.toUpperCase(Locale.ROOT));
    }

    FieldConnector connector(String fieldId, String parserCode) {
      return new FieldConnector(
          fieldId,
          location,
          selector,
          extract,
          parserCode == null ? parser : new Parser(parserCode),
          detailNavigation);
    }
  }
}
