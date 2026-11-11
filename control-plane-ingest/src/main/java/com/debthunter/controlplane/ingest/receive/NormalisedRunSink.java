package com.debthunter.controlplane.ingest.receive;

import com.debthunter.controlplane.ingest.normalise.NormalisedRunBundle;

/**
 * The stage after normalisation. Failure is signalled by throwing, which means "not processed": the
 * bundle will be redelivered and tried again.
 */
@FunctionalInterface
public interface NormalisedRunSink {

  /**
   * Accepts a normalised bundle.
   *
   * @param bundle the bundle, which still carries raw contributor identity
   * @throws RuntimeException if it could not be accepted and should be retried
   */
  void accept(NormalisedRunBundle bundle);
}
