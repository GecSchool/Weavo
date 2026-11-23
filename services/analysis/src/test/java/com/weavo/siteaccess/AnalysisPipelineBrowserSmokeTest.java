package com.weavo.siteaccess;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.weavo.analysis.approval.application.FinalAnalysis;
import com.weavo.analysis.job.application.AnalysisJobHandlers;
import com.weavo.analysis.job.application.AnalysisJobHandlers.Purpose;
import com.weavo.analysis.job.infrastructure.messaging.AnalysisJobProcessor;
import com.weavo.analysis.job.infrastructure.messaging.ContractJson;
import com.weavo.analysis.job.infrastructure.messaging.Messages.AnalysisExecute;
import com.weavo.analysis.job.infrastructure.messaging.Messages.AnalysisSource;
import com.weavo.analysis.joint.application.AnalysisArtifacts;
import com.weavo.analysis.joint.application.JointAnalysis;
import com.weavo.analysis.joint.application.JointRecommender;
import com.weavo.analysis.source.application.SourceCandidate;
import com.weavo.analysis.source.application.SourceCandidateGenerator;
import com.weavo.analysis.source.application.SourceCandidateGenerator.DetailField;
import com.weavo.analysis.source.application.SourceCandidateGenerator.DetailInput;
import com.weavo.analysis.source.application.SourceCandidateGenerator.Input;
import com.weavo.analysis.source.application.SourceDiscovery;
import com.weavo.analysis.source.infrastructure.http.OpenAiCandidateGenerator;
import com.weavo.analysis.source.infrastructure.http.OpenAiJointRecommender;
import com.weavo.extraction.FieldValueConverter.Type;
import com.weavo.extraction.JavaFieldParser;
import com.weavo.extraction.infrastructure.IsolatedParserClient;
import com.weavo.siteaccess.SourceAnalysisBrowserSmokeTest.TestSite;
import com.weavo.siteaccess.rule.FieldConnector;
import com.weavo.siteaccess.rule.FieldConnector.DetailAccess;
import com.weavo.siteaccess.rule.FieldConnector.DetailAccessMethod;
import com.weavo.siteaccess.rule.FieldConnector.DetailNavigation;
import com.weavo.siteaccess.rule.FieldConnector.DetailOpen;
import com.weavo.siteaccess.rule.FieldConnector.DetailReturn;
import com.weavo.siteaccess.rule.FieldConnector.Extract;
import com.weavo.siteaccess.rule.FieldConnector.ExtractType;
import com.weavo.siteaccess.rule.FieldConnector.Location;
import com.weavo.siteaccess.rule.FieldConnector.OpenType;
import com.weavo.siteaccess.rule.FieldConnector.ReturnType;
import com.weavo.siteaccess.rule.FieldConnector.Target;
import com.weavo.siteaccess.rule.Navigation;
import com.weavo.siteaccess.rule.Navigation.AccessMethod;
import com.weavo.siteaccess.rule.Navigation.ListAccess;
import com.weavo.siteaccess.rule.Navigation.Pagination;
import com.weavo.siteaccess.rule.Navigation.PaginationType;
import com.weavo.worker.application.AttemptContext;
import com.weavo.worker.application.JobProcessor.Job;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfSystemProperty(named = "weavo.browser.smoke", matches = "true")
class AnalysisPipelineBrowserSmokeTest {
  private static final Extract TEXT = new Extract(ExtractType.TEXT, null);

  @Test
  void feedsRefinedFieldsIntoJointRecommendationAndStoresVerifiedRevisionSamples()
      throws Exception {
    var stored = new LinkedHashMap<String, byte[]>();
    var artifacts =
        new AnalysisArtifacts(
            "test-bucket", stored::get, (key, mime, bytes) -> stored.put(key, bytes));
    var generator = fixtureGenerator();
    var parser = new JavaFieldParser();
    runPipeline(
        generator,
        AnalysisPipelineBrowserSmokeTest::recommendFixtureFields,
        (code, values, budget) -> parser.parse(code, values),
        artifacts,
        stored::get);
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "WEAVO_RUN_OPENAI_SMOKE", matches = "1")
  @EnabledIfEnvironmentVariable(named = "WEAVO_ANALYSIS_TEST_AWS", matches = ".+")
  void terraDiscoversAndCombinesTwoSourcesAndPersistsExactApprovalValues() throws Exception {
    String key = System.getenv("OPENAI_API_KEY");
    String model = OpenAiCandidateGenerator.DEFAULT_MODEL;
    var parser =
        new IsolatedParserClient(
            URI.create(System.getenv("WEAVO_PARSER_URL")), System.getenv("WEAVO_PARSER_TOKEN"));
    String bucket = "weavo-local-artifacts";
    var written = new ArrayList<String>();
    try (var s3 =
        S3Client.builder()
            .endpointOverride(URI.create(System.getenv("WEAVO_ANALYSIS_TEST_AWS")))
            .region(Region.AP_NORTHEAST_2)
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
            .forcePathStyle(true)
            .build()) {
      AnalysisArtifacts.Read read =
          object -> s3.getObjectAsBytes(r -> r.bucket(bucket).key(object)).asByteArray();
      var artifacts =
          new AnalysisArtifacts(
              bucket,
              read,
              (object, mime, bytes) -> {
                s3.putObject(
                    r -> r.bucket(bucket).key(object).contentType(mime),
                    RequestBody.fromBytes(bytes));
                written.add(object);
              });
      try {
        runPipeline(
            new OpenAiCandidateGenerator(key, model),
            new OpenAiJointRecommender(key, model),
            (code, values, budget) ->
                parser.parse(code, values, budget::beforeRequest, budget::remainingWhileWaiting),
            artifacts,
            read);
      } finally {
        for (String object : written) {
          s3.deleteObject(r -> r.bucket(bucket).key(object));
        }
      }
    }
  }

  private static void runPipeline(
      SourceCandidateGenerator generator,
      JointRecommender recommender,
      SourceDiscovery.ParserExecutor parser,
      AnalysisArtifacts artifacts,
      AnalysisArtifacts.Read read)
      throws Exception {
    try (var site = new TestSite(false)) {
      String project = UUID.randomUUID().toString();
      String detailSource = UUID.randomUUID().toString();
      String inlineSource = UUID.randomUUID().toString();
      var discovery = new SourceDiscovery(site.access(), generator, parser);
      var analysis =
          new FinalAnalysis(
              new JointAnalysis(discovery::analyze, artifacts, recommender),
              discovery::verify,
              artifacts);
      var request =
          new AnalysisExecute(
              UUID.randomUUID().toString(),
              project,
              "INITIAL",
              4L,
              "Collect only the exact title and full body of each university notice. "
                  + "The body may already be in the list item or on the linked detail page. "
                  + "Exclude sponsored entries and include notices on the next page if there is one.",
              150,
              List.of(
                  new AnalysisSource(detailSource, null, site.url("/bulletins")),
                  new AnalysisSource(inlineSource, null, site.url("/briefs"))),
              null,
              null,
              null,
              null);
      var now = Instant.now();
      var attempt = mock(AttemptContext.class);
      when(attempt.id()).thenReturn(UUID.randomUUID());
      when(attempt.now()).thenAnswer(call -> Instant.now());
      when(attempt.workDeadline()).thenReturn(now.plusSeconds(150));
      var processor =
          new AnalysisJobProcessor(
              new ContractJson(),
              new AnalysisJobHandlers(Map.of(Purpose.INITIAL, analysis::execute)));
      var event =
          processor.execute(
              new Job<>(
                  UUID.randomUUID(),
                  now.plusSeconds(180).toString(),
                  Duration.ofSeconds(150),
                  request),
              attempt);
      var payload = JsonMapper.shared().readTree(event.body()).path("payload");
      assertThat(payload.path("status").asText()).as(payload.toString()).isEqualTo("SUCCEEDED");
      assertThat(payload.path("recommendation_sample_count").asInt()).isEqualTo(4);
      assertThat(payload.at("/candidate/schema/fields")).hasSize(2);
      var mappings = payload.at("/candidate/candidate_mappings");
      assertThat(mappings).hasSize(4);
      Map<String, List<String>> mappedSources = new LinkedHashMap<>();
      mappings.forEach(mapping ->
          mappedSources
              .computeIfAbsent(mapping.path("field_id").asText(), field -> new ArrayList<>())
              .add(mapping.path("project_source_id").asText()));
      assertThat(mappedSources).hasSize(2);
      mappedSources
          .values()
          .forEach(
              sources -> assertThat(sources).containsExactlyInAnyOrder(detailSource, inlineSource));
      assertThat(payload.path("source_results")).hasSize(2);
      SampleFields sharedFields = null;
      for (var source : payload.path("source_results")) {
        String id = source.path("project_source_id").asText();
        assertThat(source.path("validation_status").asText()).isEqualTo("PASSED");
        var intermediate =
            JsonMapper.shared().readTree(read.get(source.at("/intermediate_ref/key").asText()));
        var approval =
            JsonMapper.shared().readTree(read.get(source.at("/sample_ref/key").asText()));
        assertThat(intermediate.path("project_source_id").asText()).isEqualTo(id);
        assertThat(approval.path("revision").asLong()).isEqualTo(4);
        assertThat(approval.path("project_source_id").asText()).isEqualTo(id);
        assertThat(approval.path("samples")).hasSize(2);
        SampleFields first;
        SampleFields second;
        if (id.equals(detailSource)) {
          first = assertSample(approval.at("/samples/0"), "Scholarship applications", "Body 1");
          second = assertSample(approval.at("/samples/1"), "Library hours", "Body 2");
          assertThat(approval.at("/samples/0/source_url").asText()).endsWith("/detail/1");
        } else {
          assertThat(id).isEqualTo(inlineSource);
          first = assertSample(approval.at("/samples/0"), "Club fair", "Meet in the main hall.");
          second =
              assertSample(
                  approval.at("/samples/1"), "Campus shuttle", "The shuttle runs until six.");
        }
        assertThat(second).isEqualTo(first);
        if (sharedFields != null) {
          assertThat(first).isEqualTo(sharedFields);
        }
        sharedFields = first;
      }
      assertThat(site.nextRequests()).isGreaterThanOrEqualTo(2);
      for (var config : payload.at("/candidate/source_configs")) {
        assertThat(config.at("/navigation/list_access/method").asText()).isEqualTo("HTTP");
      }
    }
  }

  private static SampleFields assertSample(JsonNode sample, String title, String body) {
    var values = new LinkedHashMap<String, String>();
    sample
        .path("fields")
        .forEach(field -> {
          assertThat(field.path("status").asText()).isEqualTo("SUCCESS");
          values.put(field.path("value").asText(), field.path("field_id").asText());
        });
    assertThat(values.keySet()).containsExactlyInAnyOrder(title, body);
    return new SampleFields(values.get(title), values.get(body));
  }

  private record SampleFields(String title, String body) {}

  private static SourceCandidateGenerator fixtureGenerator() throws InterruptedException {
    var generator = mock(SourceCandidateGenerator.class);
    when(generator.propose(any(), any()))
        .thenAnswer(call -> {
          Input input = call.getArgument(0);
          boolean detail = input.listUrl().endsWith("/bulletins");
          var navigation =
              new Navigation(
                  input.listUrl(),
                  List.of(),
                  new ListAccess(AccessMethod.BROWSER, "main"),
                  "article.notice-card",
                  new Pagination(
                      detail ? PaginationType.NEXT_LINK : PaginationType.NONE,
                      detail ? "a.next" : null,
                      detail ? "href" : null,
                      "main",
                      null,
                      null,
                      null,
                      null));
          var open =
              detail
                  ? new DetailNavigation(
                      new DetailAccess(DetailAccessMethod.BROWSER, "body"),
                      new DetailOpen(OpenType.LINK, "a.notice-details", "href", Target.CURRENT_TAB),
                      new DetailReturn(ReturnType.HISTORY_BACK, "main", null))
                  : null;
          return new SourceCandidate(
              navigation,
              List.of(
                  new SourceCandidate.Field(
                      "Title",
                      Type.STRING,
                      false,
                      "Notice heading",
                      new FieldConnector(
                          "title", Location.LIST, "h2.notice-title", TEXT, null, null)),
                  new SourceCandidate.Field(
                      "Body",
                      Type.STRING,
                      false,
                      "Full notice text",
                      new FieldConnector(
                          "body",
                          detail ? Location.DETAIL : Location.LIST,
                          detail ? "body" : ".notice-body",
                          TEXT,
                          null,
                          open))));
        });
    when(generator.refineDetails(any(), any()))
        .thenAnswer(call -> {
          DetailInput input = call.getArgument(0);
          assertThat(input.samples())
              .singleElement()
              .satisfies(
                  sample -> assertThat(sample.dom()).contains("Body 1", "Share this notice"));
          return List.of(new DetailField("body", ".body", TEXT, null));
        });
    return generator;
  }

  private static List<JointRecommender.Field> recommendFixtureFields(
      JointRecommender.Input input, WorkBudget budget) {
    assertThat(input.sources()).hasSize(2);
    assertThat(input.sources().getFirst().fields().get(1).samples())
        .containsExactly("Body 1", "Body 2");
    return List.of(
        recommendFixtureField(input, "title", "Title"),
        recommendFixtureField(input, "body", "Body"));
  }

  private static JointRecommender.Field recommendFixtureField(
      JointRecommender.Input input, String apiName, String label) {
    return new JointRecommender.Field(
        null,
        apiName,
        label,
        Type.STRING,
        "Shared notice field",
        input.sources().stream()
            .map(source -> new JointRecommender.Mapping(source.projectSourceId(), apiName, null))
            .toList());
  }
}
