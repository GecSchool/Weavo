package com.weavo.crawler.collection.application;

import com.weavo.crawler.collection.infrastructure.messaging.Messages.CollectionProject;
import com.weavo.crawler.collection.infrastructure.messaging.Messages.MessageError;
import com.weavo.extraction.FieldExtractor;
import com.weavo.extraction.FieldValueConverter.Type;
import com.weavo.extraction.FieldValueProcessor;
import com.weavo.extraction.HtmlScope;
import com.weavo.siteaccess.SiteAccess;
import com.weavo.siteaccess.SiteAccessException;
import com.weavo.siteaccess.WorkBudget;
import com.weavo.siteaccess.rule.FieldConnector;
import com.weavo.siteaccess.rule.FieldConnector.DetailAccess;
import com.weavo.siteaccess.rule.FieldConnector.DetailNavigation;
import com.weavo.siteaccess.rule.FieldConnector.DetailOpen;
import com.weavo.siteaccess.rule.FieldConnector.DetailReturn;
import com.weavo.siteaccess.rule.FieldConnector.OpenType;
import com.weavo.siteaccess.rule.FieldConnector.Target;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

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

  boolean hasDetails(Predicate<String> participates) {
    return detailGroups.values().stream()
        .flatMap(List::stream)
        .anyMatch(field -> participates.test(field.projectSourceId()));
  }

  List<DetailVisit> detailVisits(HtmlScope item, Set<String> participants) {
    Map<VisitKey, DetailVisit> visits = new LinkedHashMap<>();
    for (var entry : detailGroups.entrySet()) {
      var fields =
          entry.getValue().stream()
              .filter(field -> participants.contains(field.projectSourceId()))
              .toList();
      if (fields.isEmpty()) {
        continue;
      }
      var rule = entry.getKey();
      var open = rule.open();
      URI address = open.type() == OpenType.LINK ? shareableLink(item, open) : null;
      var key =
          new VisitKey(
              rule.access(),
              open.target(),
              rule.returnRule(),
              address,
              address == null ? open : null);
      visits
          .computeIfAbsent(key, ignored -> new DetailVisit(rule, new ArrayList<>()))
          .fields()
          .addAll(fields);
    }
    return List.copyOf(visits.values());
  }

  private static URI shareableLink(HtmlScope item, DetailOpen open) {
    try {
      String link = item.uniqueLink(open.selector(), open.urlAttribute());
      return link == null ? null : SiteAccess.normalizeAddress(link);
    } catch (IllegalArgumentException | IllegalStateException | SiteAccessException error) {
      // A rule that cannot be grouped is validated on its own under the detail navigation guard.
      return null;
    }
  }

  record DetailVisit(DetailNavigation rule, List<Field> fields) {}

  private record VisitKey(
      DetailAccess access, Target target, DetailReturn returnRule, URI link, DetailOpen action) {}

  List<FieldFailure> extract(
      List<Field> fields,
      HtmlScope scope,
      WorkBudget budget,
      Map<String, Map<String, Object>> values) {
    var failures = new ArrayList<FieldFailure>();
    for (Field field : fields) {
      var target = values.get(field.projectSourceId());
      if (target == null) {
        continue;
      }
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
        values.remove(field.projectSourceId());
        failures.add(
            new FieldFailure(
                field.projectSourceId(),
                connector.fieldId(),
                extraction.rawValues(),
                new MessageError(
                    code,
                    "CRAWLER_EXTRACTION",
                    "필드 추출 결과를 검증하지 못했습니다.",
                    Map.of("field_id", connector.fieldId(), "reason", result.failure().reason()),
                    false,
                    null)));
        continue;
      }
      target.put(connector.fieldId(), result.value());
    }
    return failures;
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

  record FieldFailure(
      String projectSourceId, String fieldId, List<String> rawValues, MessageError error) {}
}
