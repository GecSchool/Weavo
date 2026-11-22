package com.weavo.analysis.source.application;

import com.weavo.siteaccess.WorkBudget;

/** Provider boundary for proposing or correcting a source's candidate from its first DOM. */
@FunctionalInterface
public interface SourceCandidateGenerator {
  /** Produces a proposal before the deadline, or null when no further proposal is available. */
  SourceCandidate propose(Input input, WorkBudget budget) throws InterruptedException;

  record Input(
      String listUrl,
      String collectionPurpose,
      String firstListDom,
      SourceCandidate previousCandidate,
      SourceAnalyzer.Failure previousFailure) {}
}
