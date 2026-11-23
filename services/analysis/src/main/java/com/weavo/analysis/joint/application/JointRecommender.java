package com.weavo.analysis.joint.application;

import com.weavo.analysis.job.infrastructure.messaging.Messages.SchemaField;
import com.weavo.extraction.FieldValueConverter.Type;
import com.weavo.siteaccess.WorkBudget;
import java.util.List;

@FunctionalInterface
public interface JointRecommender {
  List<Field> recommend(Input input, WorkBudget budget);

  record Input(String collectionPurpose, List<SchemaField> existingFields, List<Source> sources) {}

  record Source(String projectSourceId, List<ObservedField> fields) {}

  record ObservedField(
      String candidateKey, String name, Type type, String reason, List<Object> samples) {}

  record Field(
      String existingFieldId,
      String apiName,
      String displayName,
      Type type,
      String reason,
      List<Mapping> mappings) {}

  record Mapping(String projectSourceId, String candidateKey, String parserCode) {}
}
