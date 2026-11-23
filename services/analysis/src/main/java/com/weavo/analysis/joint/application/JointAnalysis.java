package com.weavo.analysis.joint.application;

import com.weavo.analysis.job.infrastructure.messaging.Messages.*;
import com.weavo.analysis.source.application.SourceAnalyzer;
import com.weavo.siteaccess.WorkBudget;
import com.weavo.siteaccess.rule.FieldConnector;
import com.weavo.siteaccess.rule.FieldConnector.Location;
import com.weavo.worker.application.AttemptContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

public final class JointAnalysis {
  private static final int SAMPLE_LIMIT = 50;
  private static final Pattern API_NAME = Pattern.compile("[a-z][a-z0-9_]{0,63}");

  private final Discovery discovery;
  private final AnalysisArtifacts artifacts;
  private final JointRecommender recommender;

  public JointAnalysis(
      Discovery discovery, AnalysisArtifacts artifacts, JointRecommender recommender) {
    this.discovery = discovery;
    this.artifacts = artifacts;
    this.recommender = recommender;
  }

  public Result prepare(String jobId, AnalysisExecute request, AttemptContext attempt)
      throws InterruptedException {
    if (!List.of("INITIAL", "RETRY_SOURCE").contains(request.purpose())) {
      throw new IllegalArgumentException("Joint analysis requires INITIAL or RETRY_SOURCE");
    }
    var budget =
        new WorkBudget(
            attempt.workDeadline(), attempt::isCancellationRequested, attempt::requireWorkAllowed);
    Map<String, Material> materials = new LinkedHashMap<>();
    Map<String, SourceAnalyzer.Failure> failures = new LinkedHashMap<>();
    Set<String> incoming = new HashSet<>();
    for (var source : request.sources()) {
      String id = source.projectSourceId();
      if (!incoming.add(id)) {
        throw new IllegalArgumentException("Duplicate joint analysis input");
      }
      var result = discovery.analyze(source.url(), request.collectionPurpose(), attempt);
      if (result.failure() != null) {
        failures.put(id, result.failure());
        continue;
      }
      var body = intermediate(jobId, request, source, result, attempt);
      String key =
          prefix(request, id) + AnalysisArtifacts.attemptPath(jobId, attempt) + "/result.json";
      var ref = artifacts.put(key, body, attempt);
      // Joint recommendation consumes the actual immutable storage representation.
      materials.put(id, new Material(ref, read(request, id, ref, attempt)));
    }
    if (materials.isEmpty()) {
      return new Result(null, Map.of(), Map.copyOf(failures), 0);
    }

    var fresh = Set.copyOf(materials.keySet());
    var previous = request.purpose().equals("RETRY_SOURCE") ? request.currentCandidate() : null;
    if (request.purpose().equals("RETRY_SOURCE")) {
      if (previous == null || previous.schema() == null) {
        throw new IllegalArgumentException("Retry requires an existing schema");
      }
      for (var old : request.intermediateRefs()) {
        String id = old.projectSourceId();
        if (incoming.contains(id) || previous.excludedProjectSourceIds().contains(id)) {
          continue;
        }
        boolean known =
            previous.candidateMappings().stream()
                .anyMatch(mapping ->
                    id.equals(mapping.projectSourceId())
                        && old.intermediateRef().equals(mapping.intermediateRef()));
        if (!known || materials.containsKey(id)) {
          throw new IllegalArgumentException(
              "Retry intermediate does not belong to the current candidate");
        }
        materials.put(
            id,
            new Material(old.intermediateRef(), read(request, id, old.intermediateRef(), attempt)));
      }
    }
    var sampleCounts = allocateSamples(materials);
    var oldFields =
        previous == null || previous.schema() == null
            ? List.<SchemaField>of()
            : previous.schema().fields();
    var fields =
        recommender.recommend(
            new JointRecommender.Input(
                request.collectionPurpose(), oldFields, samples(materials, sampleCounts)),
            budget);
    budget.check();
    var candidate = build(fields, materials, previous, fresh, failures);
    Map<String, ObjectReference> refs = new LinkedHashMap<>();
    materials.forEach((id, material) -> {
      if (!failures.containsKey(id)) {
        refs.put(id, material.ref());
      }
    });
    return new Result(
        candidate,
        Map.copyOf(refs),
        Map.copyOf(failures),
        sampleCounts.values().stream().mapToInt(Integer::intValue).sum());
  }

  private SourceIntermediate read(
      AnalysisExecute request, String id, ObjectReference ref, AttemptContext attempt)
      throws InterruptedException {
    String base = prefix(request, id);
    if (!ref.key().startsWith(base)) {
      throw new IllegalArgumentException("Intermediate object is outside the requested source");
    }
    var body = artifacts.get(ref, SourceIntermediate.class, attempt);
    String suffix = ref.key().substring(base.length());
    if (body.formatVersion() != 1
        || !request.analysisId().equals(body.analysisId())
        || !request.projectId().equals(body.projectId())
        || !id.equals(body.projectSourceId())
        || !suffix.matches("/" + Pattern.quote(body.jobId()) + "/[0-9a-f-]{36}/result\\.json")
        || !"PASSED".equals(body.validationStatus())) {
      throw new IllegalArgumentException("Invalid intermediate object ownership or format");
    }
    body.sampleCount();
    return body;
  }

  private static String prefix(AnalysisExecute request, String id) {
    return "analysis/" + request.analysisId() + "/intermediate/" + id;
  }

  private static SourceIntermediate intermediate(
      String jobId,
      AnalysisExecute request,
      AnalysisSource source,
      SourceAnalyzer.Result result,
      AttemptContext attempt) {
    var candidate = result.candidate();
    var samples = result.samples().stream().limit(SAMPLE_LIMIT).toList();
    var fields =
        candidate.fields().stream()
            .map(field -> {
              var rule = field.connector();
              return new SourceIntermediate.Field(
                  rule.fieldId(),
                  field.name(),
                  field.type().name().toLowerCase(Locale.ROOT),
                  samples.stream().map(sample -> sample.get(rule.fieldId())).toList(),
                  rule.location(),
                  rule.selector(),
                  rule.extract(),
                  rule.detailNavigation(),
                  rule.parser(),
                  field.reason(),
                  "PASSED");
            })
            .toList();
    return new SourceIntermediate(
        1,
        request.analysisId(),
        jobId,
        request.projectId(),
        source.projectSourceId(),
        source.url(),
        attempt.now().toString(),
        fields,
        candidate.navigation(),
        fields.stream()
            .map(SourceIntermediate.Field::detailNavigation)
            .filter(Objects::nonNull)
            .distinct()
            .toList(),
        "PASSED");
  }

  private static Map<String, Integer> allocateSamples(Map<String, Material> materials) {
    Map<String, Integer> counts = new LinkedHashMap<>();
    var available = new LinkedHashMap<String, Integer>();
    materials.forEach((id, value) -> available.put(id, value.body().sampleCount()));
    int used = 0;
    for (int offset = 0; used < SAMPLE_LIMIT && offset < SAMPLE_LIMIT; offset++) {
      for (var entry : available.entrySet()) {
        if (offset < entry.getValue()) {
          counts.merge(entry.getKey(), 1, Integer::sum);
          if (++used == SAMPLE_LIMIT) {
            break;
          }
        }
      }
    }
    return counts;
  }

  private static List<JointRecommender.Source> samples(
      Map<String, Material> materials, Map<String, Integer> allocated) {
    return materials.entrySet().stream()
        .map(entry ->
            new JointRecommender.Source(
                entry.getKey(),
                entry.getValue().body().fields().stream()
                    .filter(field -> field.location() != Location.ABSENT)
                    .map(field ->
                        new JointRecommender.ObservedField(
                            field.candidateKey(),
                            field.name(),
                            field.type(),
                            field.reason(),
                            field
                                .sampleValues()
                                .subList(0, allocated.getOrDefault(entry.getKey(), 0))))
                    .toList()))
        .toList();
  }

  private static Candidate build(
      List<JointRecommender.Field> proposal,
      Map<String, Material> materials,
      Candidate previous,
      Set<String> incoming,
      Map<String, SourceAnalyzer.Failure> failures) {
    var fields = new LinkedHashMap<String, SchemaField>();
    var names = new HashSet<String>();
    var configs = new LinkedHashMap<String, SourceConfig>();
    var mappings = new ArrayList<CandidateMapping>();
    if (previous != null) {
      previous
          .schema()
          .fields()
          .forEach(field -> {
            fields.put(field.fieldId(), field);
            names.add(field.apiName());
          });
      previous.sourceConfigs().forEach(config -> configs.put(config.projectSourceId(), config));
    }
    var rules = new LinkedHashMap<String, Map<String, FieldConnector>>();
    materials.keySet().stream()
        .filter(incoming::contains)
        .forEach(id -> rules.put(id, new LinkedHashMap<>()));
    var coverage = new HashSet<Origin>();
    var split = new LinkedHashMap<Origin, List<String>>();
    var seenFields = new HashSet<String>();
    for (JointRecommender.Field proposed : proposal) {
      String fieldId = proposed.existingFieldId();
      if (fieldId == null && previous != null) {
        continue;
      }
      if (fieldId == null) {
        if (proposed.apiName() == null
            || !API_NAME.matcher(proposed.apiName()).matches()
            || !names.add(proposed.apiName())
            || proposed.displayName() == null
            || proposed.displayName().isBlank()
            || proposed.reason() == null
            || proposed.reason().isBlank()
            || proposed.type() == null) {
          throw new IllegalArgumentException("Invalid recommended schema field");
        }
        fieldId = UUID.randomUUID().toString();
        fields.put(
            fieldId,
            new SchemaField(
                fieldId,
                proposed.apiName(),
                proposed.displayName(),
                null,
                fields.size(),
                proposed.type().name().toLowerCase(Locale.ROOT),
                true,
                proposed.reason()));
      } else if (!fields.containsKey(fieldId)) {
        throw new IllegalArgumentException(
            "Joint recommendation references an unknown existing field");
      }
      if (!seenFields.add(fieldId)
          || proposed.mappings() == null
          || proposed.mappings().isEmpty()) {
        throw new IllegalArgumentException("Recommended field mapping is missing or ambiguous");
      }
      boolean newlyMapped = false;
      var sources = new HashSet<String>();
      for (var mapping : proposed.mappings()) {
        String id = mapping.projectSourceId();
        var material = materials.get(id);
        if (material == null || !sources.add(id)) {
          throw new IllegalArgumentException(
              "Joint proposal mapped several fields of one source into a field");
        }
        var nativeField =
            material.body().fields().stream()
                .filter(f ->
                    f.candidateKey().equals(mapping.candidateKey())
                        && f.location() != Location.ABSENT)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown source candidate key"));
        if (!incoming.contains(id)) {
          continue; // Existing user mappings are authoritative for other sources.
        }
        newlyMapped = true;
        var origin = new Origin(id, nativeField.candidateKey());
        coverage.add(origin);
        split.computeIfAbsent(origin, ignored -> new ArrayList<>()).add(mapping.parserCode());
        rules.get(id).put(fieldId, nativeField.connector(fieldId, mapping.parserCode()));
        mappings.add(new CandidateMapping(id, material.ref(), nativeField.candidateKey(), fieldId));
      }
      if (!newlyMapped && proposed.existingFieldId() == null) {
        throw new IllegalArgumentException("New field has no mapping to an analyzed source");
      }
    }
    if (fields.isEmpty()
        || split.values().stream()
            .anyMatch(codes ->
                codes.size() > 1
                    && codes.stream().anyMatch(code -> code == null || code.isBlank()))) {
      throw new IllegalArgumentException("Split mappings require per-output parsing rules");
    }
    for (var entry : rules.entrySet()) {
      String id = entry.getKey();
      var material = materials.get(id);
      if (previous == null) {
        for (var field : material.body().fields()) {
          if (field.location() != Location.ABSENT
              && !coverage.contains(new Origin(id, field.candidateKey()))) {
            throw new IllegalArgumentException("Joint proposal omitted an observed source field");
          }
        }
      } else if (entry.getValue().isEmpty()) {
        failures.put(
            id, new SourceAnalyzer.Failure("CANDIDATE", null, "NO_EXISTING_FIELD_MAPPING"));
        continue;
      }
      var previousConfig = configs.get(id);
      configs.put(
          id,
          new SourceConfig(
              id,
              material.body().navigation(),
              previousConfig == null ? 1L : previousConfig.connectorRevision() + 1,
              List.copyOf(entry.getValue().values())));
    }
    if (incoming.stream().allMatch(failures::containsKey)) {
      return null;
    }
    if (previous != null) {
      previous.candidateMappings().stream()
          .filter(mapping ->
              !incoming.contains(mapping.projectSourceId())
                  || failures.containsKey(mapping.projectSourceId()))
          .forEach(mappings::add);
    }
    var complete =
        configs.values().stream()
            .map(config -> {
              if (previous != null
                  && (!incoming.contains(config.projectSourceId())
                      || failures.containsKey(config.projectSourceId()))) {
                return config;
              }
              Map<String, FieldConnector> byField = new LinkedHashMap<>();
              config.fieldConnectors().forEach(rule -> byField.put(rule.fieldId(), rule));
              var connectors =
                  fields.keySet().stream()
                      .map(id ->
                          byField.getOrDefault(
                              id, new FieldConnector(id, Location.ABSENT, null, null, null, null)))
                      .toList();
              return new SourceConfig(
                  config.projectSourceId(),
                  config.navigation(),
                  config.connectorRevision(),
                  connectors);
            })
            .toList();
    return new Candidate(
        previous == null ? new Schema(List.copyOf(fields.values())) : previous.schema(),
        complete,
        previous == null ? List.of() : previous.excludedProjectSourceIds(),
        List.copyOf(mappings));
  }

  private record Material(ObjectReference ref, SourceIntermediate body) {}

  private record Origin(String id, String candidateKey) {}

  public record Result(
      Candidate candidate,
      Map<String, ObjectReference> intermediates,
      Map<String, SourceAnalyzer.Failure> failures,
      int normalSampleCount) {
    public boolean lowSample() {
      return normalSampleCount < 10;
    }
  }

  @FunctionalInterface
  public interface Discovery {
    SourceAnalyzer.Result analyze(String url, String purpose, AttemptContext attempt)
        throws InterruptedException;
  }
}
