package com.debthunter.controlplane.store.findings;

import java.util.List;

/**
 * Where findings are kept. It accepts only {@link PseudonymisedFinding}, so contributor identity
 * cannot be written here by construction.
 */
public interface PseudonymisedFindingStore {

  /**
   * Stores a run's findings, replacing any previously stored for the same run so that saving the
   * same run twice leaves one copy.
   *
   * @param runId the run
   * @param repository the repository it ran against
   * @param findings the run's findings
   */
  void saveRun(String runId, String repository, List<PseudonymisedFinding> findings);

  /**
   * Reads back a run's findings.
   *
   * @param runId the run
   * @return its findings, or an empty list if the run is unknown
   */
  List<PseudonymisedFinding> findingsForRun(String runId);
}
