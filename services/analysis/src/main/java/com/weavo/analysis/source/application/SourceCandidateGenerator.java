package com.weavo.analysis.source.application;

import com.weavo.siteaccess.WorkBudget;
import com.weavo.siteaccess.rule.FieldConnector.Extract;
import java.util.List;
import java.util.Map;

/** Provider boundary for proposing or correcting a source's candidate from its first DOM. */
public interface SourceCandidateGenerator {
  /** Produces a proposal before the deadline, or null when no further proposal is available. */
  SourceCandidate propose(Input input, WorkBudget budget) throws InterruptedException;

  /** Refines only the detail-field extraction rules using pages the browser actually opened. */
  List<DetailField> refineDetails(DetailInput input, WorkBudget budget);

  record DetailInput(String collectionPurpose, List<DetailSample> samples) {}

  record DetailSample(String dom, List<DetailTarget> fields) {}

  record DetailTarget(String fieldId, String name, String type, boolean nullable, String reason) {}

  record DetailField(String fieldId, String selector, Extract extract, String parserCode) {}

  final class Unavailable extends RuntimeException {
    public Unavailable(String reason) {
      super(reason);
    }
  }

  record Input(
      String listUrl,
      String collectionPurpose,
      String firstListDom,
      SourceCandidate previousCandidate,
      SourceAnalyzer.Failure previousFailure,
      Map<String, String> observedScreens) {
    public Input(
        String listUrl,
        String collectionPurpose,
        String firstListDom,
        SourceCandidate previousCandidate,
        SourceAnalyzer.Failure previousFailure) {
      this(listUrl, collectionPurpose, firstListDom, previousCandidate, previousFailure, Map.of());
    }
  }
}
