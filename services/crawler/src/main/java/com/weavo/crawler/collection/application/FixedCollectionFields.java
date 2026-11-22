package com.weavo.crawler.collection.application;

import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionProject;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.MessageError;
import com.weavo.extraction.FieldExtractor;
import com.weavo.extraction.FieldValueConverter.Type;
import com.weavo.extraction.FieldValueProcessor;
import com.weavo.extraction.HtmlScope;
import com.weavo.siteaccess.WorkBudget;
import com.weavo.siteaccess.rule.FieldConnector;
import com.weavo.siteaccess.rule.FieldConnector.DetailNavigation;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Supplies the scopes for fixed collection and validates values using the shared extractor. */
final class FixedCollectionFields {
  private final FieldValueProcessor processor;
  private final List<Field> listFields = new ArrayList<>();
  private final Map<DetailNavigation, List<Field>> detailGroups = new LinkedHashMap<>();

  FixedCollectionFields(List<CollectionProject> projects, FieldValueProcessor processor) {
    this.processor = processor;
    for (var project : projects) {
      var fields = new LinkedHashMap<String, FieldConnector>();
      for (var connector : project.fieldConnectors()) {
        fields.put(connector.fieldId(), connector);
      }
      for (var field : project.schema().fields()) {
        var connector = fields.get(field.fieldId());
        var rule =
            new Field(
                project.projectSourceId(),
                connector,
                Type.valueOf(field.type().toUpperCase(Locale.ROOT)),
                field.nullable());
        if (connector.location() == FieldConnector.Location.DETAIL) {
          detailGroups
              .computeIfAbsent(connector.detailNavigation(), ignored -> new ArrayList<>())
              .add(rule);
        } else {
          listFields.add(rule);
        }
      }
    }
  }

  List<Field> listFields() {
    return listFields;
  }

  Map<DetailNavigation, List<Field>> detailGroups() {
    return detailGroups;
  }

  void validate(List<Field> fields, HtmlScope scope, WorkBudget budget) {
    for (Field field : fields) {
      budget.check();
      var connector = field.connector();
      FieldExtractor.Result extraction;
      if (connector.location() == FieldConnector.Location.ABSENT) {
        extraction = FieldExtractor.absent();
      } else if (connector.extract().type() == FieldConnector.ExtractType.TEXT) {
        extraction = FieldExtractor.extractText(scope, connector.selector());
      } else {
        extraction =
            FieldExtractor.extractAttribute(
                scope, connector.selector(), connector.extract().attribute());
      }
      String parserCode = connector.parser() == null ? null : connector.parser().code();
      var result = processor.process(extraction, parserCode, field.type(), field.nullable());
      budget.check();
      if (result.failure() != null) {
        var code =
            switch (result.failure().stage()) {
              case EXTRACTION -> "FIELD_EXTRACTION_FAILED";
              case PARSER, PARSER_INPUT -> "PARSER_FAILED";
              case TYPE_CONVERSION -> "TYPE_CONVERSION_FAILED";
            };
        throw new InvalidField(
            field.projectSourceId(),
            new MessageError(
                code,
                "CRAWLER_EXTRACTION",
                "필드 추출 결과를 검증하지 못했습니다.",
                Map.of("field_id", connector.fieldId(), "reason", result.failure().reason()),
                false,
                null));
      }
    }
  }

  static List<CollectionSnapshot.Consumer> consumers(List<Field> fields) {
    Map<String, List<String>> ids = new LinkedHashMap<>();
    for (var field : fields) {
      ids.computeIfAbsent(field.projectSourceId(), ignored -> new ArrayList<>())
          .add(field.connector().fieldId());
    }
    return ids.entrySet().stream()
        .map(
            entry -> new CollectionSnapshot.Consumer(entry.getKey(), List.copyOf(entry.getValue())))
        .toList();
  }

  record Field(String projectSourceId, FieldConnector connector, Type type, boolean nullable) {}

  static final class InvalidField extends RuntimeException {
    private final String projectSourceId;
    private final MessageError error;

    InvalidField(String projectSourceId, MessageError error) {
      super(error.message());
      this.projectSourceId = projectSourceId;
      this.error = error;
    }

    String projectSourceId() {
      return projectSourceId;
    }

    MessageError error() {
      return error;
    }
  }
}
