package com.weavo.analysis;

import com.weavo.analysis.source.application.SourceDiscovery;
import com.weavo.analysis.source.infrastructure.http.OpenAiCandidateGenerator;
import com.weavo.extraction.infrastructure.IsolatedParserClient;
import com.weavo.siteaccess.SiteAccess;
import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("worker")
@ConditionalOnProperty(name = "check", havingValue = "false", matchIfMissing = true)
class SourceDiscoveryConfiguration {
  @Bean
  SourceDiscovery sourceDiscovery(
      SiteAccess sites,
      @Value("${OPENAI_API_KEY:}") String apiKey,
      @Value("${WEAVO_ANALYSIS_MODEL:" + OpenAiCandidateGenerator.DEFAULT_MODEL + "}") String model,
      @Value("${WEAVO_PARSER_URL:}") String parserService,
      @Value("${WEAVO_PARSER_TOKEN:}") String parserToken) {
    var generator = new OpenAiCandidateGenerator(apiKey, model);
    var parser = new IsolatedParserClient(URI.create(parserService), parserToken);
    return new SourceDiscovery(
        sites,
        generator,
        (code, values, budget) ->
            parser.parse(code, values, budget::beforeRequest, budget::remainingWhileWaiting));
  }
}
