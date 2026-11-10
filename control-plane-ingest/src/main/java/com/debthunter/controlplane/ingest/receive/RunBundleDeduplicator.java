package com.debthunter.controlplane.ingest.receive;

/**
 * Remembers which runs have already been fully processed, so a bundle delivered more than once is
 * handled once. The intake queue is at-least-once, so duplicates are normal, not exceptional.
 */
public interface RunBundleDeduplicator {

  /**
   * Whether a run has already been fully processed.
   *
   * @param runId the bundle's run id
   * @return {@code true} if it was previously marked processed
   */
  boolean alreadyProcessed(String runId);

  /**
   * Records that a run was fully processed. Called only after the handler has succeeded.
   *
   * @param runId the bundle's run id
   */
  void markProcessed(String runId);
}
