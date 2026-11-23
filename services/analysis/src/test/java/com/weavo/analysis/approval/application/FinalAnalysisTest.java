package com.weavo.analysis.approval.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.weavo.analysis.job.application.AnalysisJobHandlers;
import com.weavo.analysis.job.application.AnalysisJobHandlers.Purpose;
import com.weavo.analysis.job.infrastructure.messaging.AnalysisJobProcessor;
import com.weavo.analysis.job.infrastructure.messaging.CommandEnvelope;
import com.weavo.analysis.job.infrastructure.messaging.ContractJson;
import com.weavo.analysis.job.infrastructure.messaging.Messages.AnalysisExecute;
import com.weavo.analysis.job.infrastructure.messaging.Messages.AnalysisSource;
import com.weavo.analysis.job.infrastructure.messaging.Messages.Candidate;
import com.weavo.analysis.job.infrastructure.messaging.Messages.Schema;
import com.weavo.analysis.job.infrastructure.messaging.Messages.SchemaField;
import com.weavo.analysis.job.infrastructure.messaging.Messages.SourceConfig;
import com.weavo.analysis.joint.application.AnalysisArtifacts;
import com.weavo.analysis.joint.application.JointAnalysis;
import com.weavo.analysis.joint.application.JointRecommender;
import com.weavo.analysis.source.application.SourceAnalyzer;
import com.weavo.analysis.source.application.SourceCandidate;
import com.weavo.extraction.FieldExtractor;
import com.weavo.extraction.FieldValueConverter.Type;
import com.weavo.extraction.FieldValueProcessor;
import com.weavo.siteaccess.rule.FieldConnector;
import com.weavo.siteaccess.rule.FieldConnector.Location;
import com.weavo.siteaccess.rule.Navigation;
import com.weavo.worker.application.AttemptContext;
import com.weavo.worker.application.JobProcessor.Job;
import com.weavo.worker.application.JobWorker;
import com.weavo.worker.application.WorkerTiming;
import com.weavo.worker.infrastructure.AwsJobTransport;
import com.weavo.worker.infrastructure.ControlAttemptClient;
import com.weavo.worker.infrastructure.ControlAttemptClient.Claim;
import com.weavo.worker.infrastructure.ControlAttemptClient.Status;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import tools.jackson.databind.json.JsonMapper;

class FinalAnalysisTest {
  private static final String URL = "https://source.example/list";
  private static final Instant CAPTURED = Instant.parse("2026-11-23T12:00:00Z");

  @ParameterizedTest
  @ValueSource(ints = {9, 10})
  void finalizesInitialJobWithActualStoredSampleAndIntermediateReferences(int records)
      throws Exception {
    var storage = new LinkedHashMap<String, byte[]>();
    var artifacts = artifacts(storage);
    var analysis = initialAnalysis(artifacts, records);
    String participant = id();
    var request =
        new AnalysisExecute(
            id(),
            id(),
            "INITIAL",
            3L,
            "News",
            30,
            List.of(new AnalysisSource(participant, null, URL)),
            null,
            null,
            null,
            null);
    var event =
        new AnalysisJobProcessor(
                new ContractJson(),
                new AnalysisJobHandlers(Map.of(Purpose.INITIAL, analysis::execute)))
            .execute(
                new Job<>(
                    UUID.randomUUID(),
                    Instant.now().plusSeconds(60).toString(),
                    Duration.ofSeconds(30),
                    request),
                attempt());
    var payload = JsonMapper.shared().readTree(event.body()).path("payload");
    assertThat(payload.path("status").asText()).isEqualTo("SUCCEEDED");
    assertThat(payload.at("/candidate/source_configs/0/navigation/list_access/method").asText())
        .isEqualTo("HTTP");
    var key = payload.at("/source_results/0/sample_ref/key").asText();
    assertThat(key).contains("/revisions/3/" + participant + "/");
    var sample = JsonMapper.shared().readTree(storage.get(key));
    assertThat(sample.path("revision").asLong()).isEqualTo(3);
    assertThat(sample.path("samples")).hasSize(records);
    assertThat(
            JsonMapper.shared()
                .readTree(
                    storage.get(payload.at("/source_results/0/intermediate_ref/key").asText()))
                .path("project_source_id")
                .asText())
        .isEqualTo(participant);
  }

  @Test
  void writesFailedValuesOnlyToSidecarAndDistinguishesMissingMatchFromAbsent() throws Exception {
    var storage = new LinkedHashMap<String, byte[]>();
    var request = request(5L);
    var config = request.currentCandidate().sourceConfigs().getFirst();
    var ids = config.fieldConnectors().stream().map(FieldConnector::fieldId).toList();
    var bad =
        new FieldValueProcessor.Result(
            FieldExtractor.Status.FAILED,
            new FieldExtractor.Result(
                FieldExtractor.Status.SUCCESS, 1, List.of("invalid date"), null),
            null,
            new FieldValueProcessor.Failure(
                FieldValueProcessor.Stage.TYPE_CONVERSION, "INVALID_DATE", null));
    var noMatch =
        new FieldValueProcessor.Result(
            FieldExtractor.Status.FAILED,
            new FieldExtractor.Result(
                FieldExtractor.Status.FAILED, 0, List.of(), FieldExtractor.Failure.NO_MATCH),
            null,
            new FieldValueProcessor.Failure(
                FieldValueProcessor.Stage.EXTRACTION, "NO_MATCH", null));
    var absent =
        new FieldValueProcessor.Result(
            FieldExtractor.Status.ABSENT, FieldExtractor.absent(), null, null);
    var output =
        new FinalAnalysis(
                null,
                (candidate, attempt) ->
                    new SourceAnalyzer.Verification(
                        null,
                        List.of(
                            new SourceAnalyzer.Sample(
                                null,
                                List.of(
                                    new SourceAnalyzer.FieldObservation(ids.get(0), bad),
                                    new SourceAnalyzer.FieldObservation(ids.get(1), noMatch),
                                    new SourceAnalyzer.FieldObservation(ids.get(2), absent)),
                                false)),
                        new SourceAnalyzer.Failure(
                            "TYPE_CONVERSION", ids.getFirst(), "INVALID_DATE")),
                artifacts(storage))
            .execute(id(), request, attempt());
    assertThat(output.status()).isEqualTo("FAILED");
    var keys = storage.keySet().stream().toList();
    assertThat(keys.getFirst()).contains("/failures/");
    assertThat(keys.getLast()).contains("/revisions/5/");
    var sample = JsonMapper.shared().readTree(storage.get(keys.getLast()));
    var fields = sample.at("/samples/0/fields");
    assertThat(fields.get(0).path("raw_values")).isEmpty();
    assertThat(fields.get(0).path("failure_expires_at").asText()).isEqualTo("2026-11-30T12:00:00Z");
    assertThat(fields.get(1).path("match_count").asInt()).isZero();
    assertThat(fields.get(2).path("match_count").isNull()).isTrue();
    assertThat(
            JsonMapper.shared()
                .readTree(storage.get(keys.getFirst()))
                .path("raw_values")
                .get(0)
                .asText())
        .isEqualTo("invalid date");
  }

  @Test
  void aStorageFailurePreventsPublishingSuccessfulValidation() {
    var analysis =
        new FinalAnalysis(
            null,
            (candidate, attempt) ->
                new SourceAnalyzer.Verification(
                    nav(Navigation.AccessMethod.HTTP), List.of(sample(candidate)), null),
            new AnalysisArtifacts(
                "test",
                key -> null,
                (key, mime, bytes) -> {
                  throw new IllegalStateException("artifact storage unavailable");
                }));
    assertThatThrownBy(() -> analysis.execute(id(), request(7L), attempt()))
        .hasMessage("artifact storage unavailable");
  }

  @Test
  void revalidatingANewerRevisionCannotReuseThePreviousPath() throws Exception {
    var storage = new LinkedHashMap<String, byte[]>();
    var analysis =
        new FinalAnalysis(
            null,
            (candidate, attempt) ->
                new SourceAnalyzer.Verification(
                    nav(Navigation.AccessMethod.HTTP), List.of(sample(candidate)), null),
            artifacts(storage));
    var output = analysis.execute(id(), request(8L), attempt());
    var reference = output.sourceResults().getFirst().sampleRef();
    assertThat(reference.key()).contains("/revisions/8/").doesNotContain("/revisions/7/");
    assertThat(JsonMapper.shared().readTree(storage.get(reference.key())).path("revision").asLong())
        .isEqualTo(8L);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "WEAVO_ANALYSIS_TEST_AWS", matches = ".+")
  void workerConsumesSqsAndPublishesCompletionAfterBothS3ObjectsExist() throws Exception {
    URI local = URI.create(System.getenv("WEAVO_ANALYSIS_TEST_AWS"));
    String prefix = "weavo-analysis-test-" + id();
    String inputName = prefix + "-input";
    String outputName = prefix + "-result";
    String bucket = "weavo-local-artifacts";
    try (var sqs =
        SqsClient.builder()
            .endpointOverride(local)
            .region(Region.AP_NORTHEAST_2)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .build()) {
      String input = sqs.createQueue(r -> r.queueName(inputName)).queueUrl();
      String output = sqs.createQueue(r -> r.queueName(outputName)).queueUrl();
      try (var transport =
          AwsJobTransport.open(
              "ap-northeast-2",
              local,
              bucket,
              List.of(inputName),
              outputName,
              Duration.ofSeconds(8))) {
        var artifacts =
            new AnalysisArtifacts(bucket, transport::readObject, transport::storeObject);
        var analysis = initialAnalysis(artifacts, 1);
        var contract = new ContractJson();
        var processor =
            new AnalysisJobProcessor(
                contract, new AnalysisJobHandlers(Map.of(Purpose.INITIAL, analysis::execute)));
        var control = mock(ControlAttemptClient.class);
        when(control.claim(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
            .thenAnswer(call -> new Claim(Status.ACQUIRED, Instant.now().plusSeconds(60)));
        var timing =
            new WorkerTiming(
                Duration.ofSeconds(5),
                Duration.ofSeconds(30),
                Duration.ofSeconds(15),
                Duration.ofSeconds(3),
                Duration.ofSeconds(8));
        String jobId = id();
        String source = id();
        var request =
            new AnalysisExecute(
                id(),
                id(),
                "INITIAL",
                4L,
                "News",
                30,
                List.of(new AnalysisSource(source, null, URL)),
                null,
                null,
                null,
                null);
        try (var worker =
            new JobWorker<>(processor, transport, control, timing, Clock.systemUTC())) {
          worker.start();
          var now = Instant.now();
          String command =
              contract.writeCommand(
                  new CommandEnvelope<>(
                      1,
                      "analysis.execute",
                      jobId,
                      null,
                      now.toString(),
                      now.plusSeconds(60).toString(),
                      request));
          sqs.sendMessage(r -> r.queueUrl(input).messageBody(command));
          var message = sqs.receiveMessage(r -> r.queueUrl(output).waitTimeSeconds(12)).messages();
          assertThat(message).hasSize(1);
          var event = contract.readEvent(message.getFirst().body());
          assertThat(event.jobId()).isEqualTo(jobId);
          var payload = JsonMapper.shared().readTree(message.getFirst().body()).path("payload");
          assertThat(payload.path("status").asText()).isEqualTo("SUCCEEDED");
          var samples =
              JsonMapper.shared()
                  .readTree(
                      transport.readObject(
                          payload.at("/source_results/0/sample_ref/key").asText()));
          var intermediate =
              JsonMapper.shared()
                  .readTree(
                      transport.readObject(
                          payload.at("/source_results/0/intermediate_ref/key").asText()));
          assertThat(samples.path("revision").asLong()).isEqualTo(4);
          assertThat(intermediate.path("project_source_id").asText()).isEqualTo(source);
        }
      } finally {
        sqs.deleteQueue(r -> r.queueUrl(input));
        sqs.deleteQueue(r -> r.queueUrl(output));
      }
    }
  }

  private static FinalAnalysis initialAnalysis(AnalysisArtifacts artifacts, int records) {
    var discovery =
        new SourceCandidate(
            nav(Navigation.AccessMethod.BROWSER),
            List.of(
                new SourceCandidate.Field(
                    "Title", Type.STRING, true, "Title", connector("local-key", false))));
    var joint =
        new JointAnalysis(
            (url, purpose, attempt) ->
                new SourceAnalyzer.Result(discovery, List.of(Map.of("local-key", "item")), null),
            artifacts,
            (input, budget) ->
                List.of(
                    new JointRecommender.Field(
                        null,
                        "title",
                        "Title",
                        Type.STRING,
                        "Recommendation",
                        List.of(
                            new JointRecommender.Mapping(
                                input.sources().getFirst().projectSourceId(),
                                "local-key",
                                null)))));
    var analysis =
        new FinalAnalysis(
            joint,
            (candidate, attempt) ->
                new SourceAnalyzer.Verification(
                    nav(Navigation.AccessMethod.HTTP),
                    java.util.stream.IntStream.range(0, records)
                        .mapToObj(i ->
                            new SourceAnalyzer.Sample(
                                URL + "/" + i,
                                List.of(
                                    new SourceAnalyzer.FieldObservation(
                                        candidate.fields().getFirst().connector().fieldId(),
                                        new FieldValueProcessor.Result(
                                            FieldExtractor.Status.SUCCESS,
                                            new FieldExtractor.Result(
                                                FieldExtractor.Status.SUCCESS,
                                                1,
                                                List.of("item"),
                                                null),
                                            "item",
                                            null))),
                                true))
                        .toList(),
                    null),
            artifacts);
    return analysis;
  }

  private static SourceAnalyzer.Sample sample(SourceCandidate candidate) {
    var fields =
        candidate.fields().stream()
            .map(field -> {
              var status =
                  field.connector().location() == Location.ABSENT
                      ? FieldExtractor.Status.ABSENT
                      : FieldExtractor.Status.SUCCESS;
              var extraction =
                  status == FieldExtractor.Status.ABSENT
                      ? FieldExtractor.absent()
                      : new FieldExtractor.Result(status, 1, List.of("item"), null);
              return new SourceAnalyzer.FieldObservation(
                  field.connector().fieldId(),
                  new FieldValueProcessor.Result(
                      status,
                      extraction,
                      status == FieldExtractor.Status.ABSENT ? null : "item",
                      null));
            })
            .toList();
    return new SourceAnalyzer.Sample(null, fields, true);
  }

  private static AnalysisExecute request(long revision) {
    String src = id();
    var fields =
        List.of(
            new SchemaField(id(), "first", "First", null, 0, "string", true, null),
            new SchemaField(id(), "second", "Second", null, 1, "string", true, null),
            new SchemaField(id(), "third", "Third", null, 2, "string", true, null));
    var config =
        new SourceConfig(
            src,
            nav(Navigation.AccessMethod.BROWSER),
            1L,
            List.of(
                connector(fields.get(0).fieldId(), false),
                connector(fields.get(1).fieldId(), false),
                connector(fields.get(2).fieldId(), true)));
    var current = new Candidate(new Schema(fields), List.of(config), List.of(), List.of());
    return new AnalysisExecute(
        id(),
        id(),
        "REVALIDATE",
        revision,
        null,
        30,
        List.of(new AnalysisSource(src, null, URL)),
        current,
        null,
        null,
        null);
  }

  private static Navigation nav(Navigation.AccessMethod method) {
    return new Navigation(
        URL,
        List.of(),
        new Navigation.ListAccess(method, null),
        "article",
        new Navigation.Pagination(
            Navigation.PaginationType.NONE, null, null, null, null, null, null, null));
  }

  private static FieldConnector connector(String key, boolean absent) {
    return new FieldConnector(
        key,
        absent ? Location.ABSENT : Location.LIST,
        absent ? null : "h2",
        absent ? null : new FieldConnector.Extract(FieldConnector.ExtractType.TEXT, null),
        null,
        null);
  }

  private static AnalysisArtifacts artifacts(Map<String, byte[]> storage) {
    return new AnalysisArtifacts(
        "test",
        storage::get,
        (key, mime, bytes) -> {
          assertThat(storage.putIfAbsent(key, bytes)).isNull();
        });
  }

  private static AttemptContext attempt() {
    var mock = mock(AttemptContext.class);
    when(mock.id()).thenReturn(UUID.randomUUID());
    when(mock.now()).thenReturn(CAPTURED);
    when(mock.workDeadline()).thenReturn(Instant.now().plusSeconds(20));
    return mock;
  }

  private static String id() {
    return UUID.randomUUID().toString();
  }
}
