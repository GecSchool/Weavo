package com.weavo.analysis.joint.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.weavo.analysis.job.infrastructure.messaging.Messages.AnalysisExecute;
import com.weavo.analysis.job.infrastructure.messaging.Messages.AnalysisSource;
import com.weavo.analysis.job.infrastructure.messaging.Messages.IntermediateReference;
import com.weavo.analysis.source.application.SourceAnalyzer;
import com.weavo.analysis.source.application.SourceCandidate;
import com.weavo.extraction.FieldValueConverter.Type;
import com.weavo.siteaccess.rule.FieldConnector;
import com.weavo.siteaccess.rule.FieldConnector.Location;
import com.weavo.siteaccess.rule.Navigation;
import com.weavo.worker.application.AttemptContext;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import tools.jackson.databind.json.JsonMapper;

class JointAnalysisTest {
  private static final String A = "https://one.example/notices";
  private static final String B = "https://two.example/posts";

  @ParameterizedTest
  @CsvSource({"4,5,9,true", "5,5,10,false", "50,50,50,false"})
  void capsSharedSamplesAndStoresIndependentReferences(int a, int b, int count, boolean low)
      throws Exception {
    var storage = new LinkedHashMap<String, byte[]>();
    var reads = new AtomicInteger();
    var analyzer =
        new JointAnalysis(
            (url, purpose, attempt) ->
                A.equals(url) ? source(url, a, "label", "special") : source(url, b, "label"),
            artifacts(storage, reads),
            (input, budget) -> {
              assertThat(
                      input.sources().stream()
                          .mapToInt(source -> source.fields().getFirst().samples().size())
                          .sum())
                  .isEqualTo(count);
              var first = input.sources().getFirst();
              var second = input.sources().getLast();
              return List.of(
                  field("title", map(first, "label", null), map(second, "label", null)),
                  field("special", map(first, "special", null)));
            });
    var output = analyzer.prepare(id(), request(A, B), attempt());
    assertThat(reads.get()).isEqualTo(2);
    assertThat(storage).hasSize(2);
    assertThat(output.normalSampleCount()).isEqualTo(count);
    assertThat(output.lowSample()).isEqualTo(low);
    assertThat(output.candidate().schema().fields())
        .hasSize(2)
        .allSatisfy(f -> {
          assertThat(f.nullable()).isTrue();
          assertThat(f.recommendationReason()).isNotBlank();
        });
    assertThat(output.candidate().sourceConfigs().getLast().fieldConnectors().getLast().location())
        .isEqualTo(Location.ABSENT);
    assertThat(output.candidate().candidateMappings()).hasSize(3);
    for (byte[] object : storage.values()) {
      var json = JsonMapper.shared().readTree(object);
      assertThat(json.path("validation_status").asText()).isEqualTo("PASSED");
      assertThat(json.path("fields").get(0).path("suggested_type").asText()).isEqualTo("string");
      assertThat(json.has("html")).isFalse();
    }
  }

  @Test
  void retainsSeparateParsersForOneSourceFieldMappedToTwoOutputFields() throws Exception {
    var joint =
        new JointAnalysis(
            (url, purpose, attempt) -> source(url, 2, "joined"),
            artifacts(new LinkedHashMap<>(), new AtomicInteger()),
            (input, budget) ->
                List.of(
                    field(
                        "left",
                        map(
                            input.sources().getFirst(),
                            "joined",
                            "return rawValues.getFirst().split(\":\")[0];")),
                    field(
                        "right",
                        map(
                            input.sources().getFirst(),
                            "joined",
                            "return rawValues.getFirst().split(\":\")[1];"))));
    var result = joint.prepare(id(), request(A), attempt());
    assertThat(result.candidate().candidateMappings())
        .extracting(mapping -> mapping.candidateKey())
        .containsExactly("joined", "joined");
    assertThat(result.candidate().sourceConfigs().getFirst().fieldConnectors())
        .extracting(connector -> connector.parser().code())
        .doesNotHaveDuplicates();
  }

  @Test
  void preservesSuccessfulCandidatesAndNeverCallsModelWhenAllSourcesFail() throws Exception {
    var storage = new LinkedHashMap<String, byte[]>();
    var joint =
        new JointAnalysis(
            (url, purpose, attempt) -> A.equals(url) ? source(url, 1, "label") : failed(),
            artifacts(storage, new AtomicInteger()),
            (input, budget) ->
                List.of(field("title", map(input.sources().getFirst(), "label", null))));
    var partial = joint.prepare(id(), request(A, B), attempt());
    assertThat(partial.candidate().sourceConfigs()).hasSize(1);
    assertThat(partial.failures()).hasSize(1);
    var all =
        new JointAnalysis(
                (url, purpose, attempt) -> failed(),
                artifacts(storage, new AtomicInteger()),
                (input, budget) -> {
                  throw new AssertionError("Model must not be called after all sources fail");
                })
            .prepare(id(), request(B), attempt());
    assertThat(all.candidate()).isNull();
    assertThat(all.intermediates()).isEmpty();
  }

  @Test
  void keepsPreviousSchemaAndConnectorsOnSourceRetry() throws Exception {
    var storage = new LinkedHashMap<String, byte[]>();
    var firstTask = request(A);
    var initial =
        new JointAnalysis(
                (url, purpose, attempt) -> source(url, 1, "label"),
                artifacts(storage, new AtomicInteger()),
                (input, budget) ->
                    List.of(field("title", map(input.sources().getFirst(), "label", null))))
            .prepare(id(), firstTask, attempt());
    var a = firstTask.sources().getFirst();
    var b = new AnalysisSource(id(), null, B);
    var retry =
        new AnalysisExecute(
            firstTask.analysisId(),
            firstTask.projectId(),
            "RETRY_SOURCE",
            2L,
            "News",
            30,
            List.of(b),
            initial.candidate(),
            null,
            null,
            List.of(
                new IntermediateReference(
                    a.projectSourceId(), initial.intermediates().get(a.projectSourceId()))));
    var result =
        new JointAnalysis(
                (url, purpose, attempt) -> source(url, 1, "label"),
                artifacts(storage, new AtomicInteger()),
                (input, budget) -> {
                  var previous = input.existingFields().getFirst();
                  return List.of(
                      new JointRecommender.Field(
                          previous.fieldId(),
                          "changed",
                          "Changed",
                          Type.DATE,
                          "ignored",
                          List.of(map(input.sources().getFirst(), "label", null))));
                })
            .prepare(id(), retry, attempt());
    assertThat(result.candidate().schema()).isEqualTo(initial.candidate().schema());
    assertThat(result.candidate().sourceConfigs().getFirst())
        .isEqualTo(initial.candidate().sourceConfigs().getFirst());
    assertThat(result.intermediates()).hasSize(2);
  }

  @Test
  void propagatesStorageReadFailureWithoutOfferingCandidate() {
    var joint =
        new JointAnalysis(
            (url, purpose, attempt) -> source(url, 1, "label"),
            new AnalysisArtifacts(
                "test",
                key -> {
                  throw new IllegalStateException("read failed");
                },
                (key, mime, bytes) -> {}),
            (input, budget) -> {
              throw new AssertionError("never recommend");
            });
    assertThatThrownBy(() -> joint.prepare(id(), request(A), attempt())).hasMessage("read failed");
  }

  private static JointRecommender.Field field(String name, JointRecommender.Mapping... mappings) {
    return new JointRecommender.Field(
        null, name, name, Type.STRING, "Observed field", List.of(mappings));
  }

  private static JointRecommender.Mapping map(
      JointRecommender.Source source, String key, String parser) {
    return new JointRecommender.Mapping(source.projectSourceId(), key, parser);
  }

  private static AnalysisArtifacts artifacts(Map<String, byte[]> storage, AtomicInteger reads) {
    return new AnalysisArtifacts(
        "test",
        key -> {
          reads.incrementAndGet();
          return storage.get(key);
        },
        (key, mime, bytes) -> {
          assertThat(storage.putIfAbsent(key, bytes)).isNull();
        });
  }

  private static SourceAnalyzer.Result source(String url, int count, String... keys) {
    var nav =
        new Navigation(
            url,
            List.of(),
            new Navigation.ListAccess(Navigation.AccessMethod.BROWSER, null),
            "article",
            new Navigation.Pagination(
                Navigation.PaginationType.NONE, null, null, null, null, null, null, null));
    var fields =
        java.util.Arrays.stream(keys)
            .map(key ->
                new SourceCandidate.Field(
                    key,
                    Type.STRING,
                    true,
                    "Visible field",
                    new FieldConnector(
                        key,
                        Location.LIST,
                        "h2",
                        new FieldConnector.Extract(FieldConnector.ExtractType.TEXT, null),
                        null,
                        null)))
            .toList();
    var result =
        IntStream.range(0, count)
            .mapToObj(i -> {
              Map<String, Object> values = new LinkedHashMap<>();
              fields.forEach(field -> values.put(field.connector().fieldId(), "Value " + i));
              return values;
            })
            .toList();
    return new SourceAnalyzer.Result(new SourceCandidate(nav, fields), result, null);
  }

  private static SourceAnalyzer.Result failed() {
    return new SourceAnalyzer.Result(
        null, List.of(), new SourceAnalyzer.Failure("LIST", null, "NO_LIST_ITEMS"));
  }

  private static AnalysisExecute request(String... urls) {
    var sources =
        java.util.Arrays.stream(urls).map(url -> new AnalysisSource(id(), null, url)).toList();
    return new AnalysisExecute(
        id(), id(), "INITIAL", 1L, "News", 30, sources, null, null, null, null);
  }

  private static AttemptContext attempt() {
    var attempt = mock(AttemptContext.class);
    when(attempt.id()).thenReturn(UUID.randomUUID());
    when(attempt.now()).thenReturn(Instant.now());
    when(attempt.workDeadline()).thenReturn(Instant.now().plusSeconds(20));
    return attempt;
  }

  private static String id() {
    return UUID.randomUUID().toString();
  }
}
