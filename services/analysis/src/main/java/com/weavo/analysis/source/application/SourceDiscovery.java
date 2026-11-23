package com.weavo.analysis.source.application;

import com.weavo.extraction.FieldValueProcessor;
import com.weavo.extraction.JavaFieldParser;
import com.weavo.siteaccess.SiteAccess;
import com.weavo.siteaccess.WorkBudget;
import com.weavo.worker.application.AttemptContext;
import java.util.List;

/** Discovers one source within the parent attempt and delegates Java execution to its executor. */
public final class SourceDiscovery {
  private final SiteAccess sites;
  private final SourceCandidateGenerator generator;
  private final ParserExecutor parser;

  public SourceDiscovery(
      SiteAccess sites, SourceCandidateGenerator generator, ParserExecutor parser) {
    this.sites = sites;
    this.generator = generator;
    this.parser = parser;
  }

  public SourceAnalyzer.Result analyze(String url, String purpose, AttemptContext attempt)
      throws InterruptedException {
    var budget =
        new WorkBudget(
            attempt.workDeadline(), attempt::isCancellationRequested, attempt::requireWorkAllowed);
    var fields = new FieldValueProcessor((code, values) -> parser.parse(code, values, budget));
    return new SourceAnalyzer(sites, fields, generator).analyze(url, purpose, budget);
  }

  @FunctionalInterface
  public interface ParserExecutor {
    JavaFieldParser.Result parse(String code, List<String> values, WorkBudget budget);
  }
}
