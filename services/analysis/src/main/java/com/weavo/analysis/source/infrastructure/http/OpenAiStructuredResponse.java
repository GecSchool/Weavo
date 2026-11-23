package com.weavo.analysis.source.infrastructure.http;

import com.weavo.analysis.source.application.SourceCandidateGenerator.Unavailable;
import com.weavo.siteaccess.WorkBudget;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.Map;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class OpenAiStructuredResponse {
  static final URI ENDPOINT = URI.create("https://api.openai.com/v1/responses");
  private static final Duration CALL_TIMEOUT = Duration.ofSeconds(60);
  private final AnalysisHttpClient http;
  private final URI endpoint;
  private final String apiKey;
  private final String model;
  private final JsonMapper json = JsonMapper.shared();
  private final JsonNode schema;

  OpenAiStructuredResponse(
      HttpClient http, URI endpoint, String apiKey, String model, String schemaPath) {
    if (apiKey == null || apiKey.isBlank() || model == null || model.isBlank()) {
      throw new IllegalArgumentException("OPENAI_API_KEY and model configuration are required");
    }
    this.http = new AnalysisHttpClient(http);
    this.endpoint = endpoint;
    this.apiKey = apiKey;
    this.model = model;
    try (var stream = getClass().getResourceAsStream(schemaPath)) {
      schema = json.readTree(stream);
    } catch (IOException error) {
      throw new IllegalStateException("Cannot read the structured analysis schema", error);
    }
  }

  String request(String schemaName, String instructions, Object evidence, WorkBudget budget) {
    var body =
        Map.of(
            "model",
            model,
            "instructions",
            instructions,
            "input",
            json.writeValueAsString(evidence),
            "text",
            Map.of(
                "format",
                Map.of(
                    "type", "json_schema", "name", schemaName, "strict", true, "schema", schema)),
            "reasoning",
            Map.of("effort", "low"),
            "max_output_tokens",
            6000,
            "store",
            false);
    var remaining = budget.remaining();
    var request =
        HttpRequest.newBuilder(endpoint)
            .timeout(remaining.compareTo(CALL_TIMEOUT) < 0 ? remaining : CALL_TIMEOUT)
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + apiKey)
            .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
            .build();

    try {
      var response = http.send(request, budget);
      budget.check();
      if (response.statusCode() != 200) {
        throw new Unavailable("HTTP_" + response.statusCode());
      }
      return output(response.body());
    } catch (AnalysisHttpClient.Unavailable error) {
      throw new Unavailable("HTTP_UNAVAILABLE");
    }
  }

  private String output(String response) {
    try {
      var envelope = json.readTree(response);
      if (!"completed".equals(envelope.path("status").asText())) {
        throw new Unavailable("RESPONSE_INCOMPLETE");
      }
      String output = null;
      for (var item : envelope.path("output")) {
        if (!"message".equals(item.path("type").asText())) {
          continue;
        }
        for (var content : item.path("content")) {
          if ("refusal".equals(content.path("type").asText())) {
            throw new Unavailable("RESPONSE_REFUSED");
          }
          if ("output_text".equals(content.path("type").asText())) {
            if (output != null || !content.path("text").isTextual()) {
              throw new Unavailable("INVALID_RESPONSE");
            }
            output = content.path("text").asText();
          }
        }
      }
      if (output == null) {
        throw new Unavailable("INVALID_RESPONSE");
      }
      return output;
    } catch (JacksonException invalid) {
      throw new Unavailable("INVALID_RESPONSE");
    }
  }
}
