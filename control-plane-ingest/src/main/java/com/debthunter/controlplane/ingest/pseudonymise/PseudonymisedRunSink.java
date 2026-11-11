package com.debthunter.controlplane.ingest.pseudonymise;

/**
 * The stage after pseudonymisation, and the first that may persist anything. Failure is signalled
 * by throwing, which means "not processed": the bundle will be redelivered.
 */
@FunctionalInterface
public interface PseudonymisedRunSink {

  /**
   * Accepts a pseudonymised bundle.
   *
   * @param bundle the bundle, safe to persist
   * @throws RuntimeException if it could not be accepted and should be retried
   */
  void accept(PseudonymisedRunBundle bundle);
}
