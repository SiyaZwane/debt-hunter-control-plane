package com.debthunter.controlplane.ingest.pseudonymise;

import com.debthunter.controlplane.ingest.normalise.NormalisedRunBundle;
import com.debthunter.controlplane.ingest.receive.NormalisedRunSink;
import java.util.Objects;

/**
 * The gate between normalisation and storage: nothing reaches {@code next} except through the
 * pseudonymiser.
 *
 * <p>If pseudonymisation fails — above all, if the key store is unavailable — the exception
 * propagates and {@code next} is never called. The bundle is therefore held and retried, never
 * processed without pseudonymisation.
 */
public final class PseudonymisingSink implements NormalisedRunSink {

  private final RunBundlePseudonymiser pseudonymiser;
  private final PseudonymisedRunSink next;

  /**
   * Creates the gate.
   *
   * @param pseudonymiser pseudonymises bundles
   * @param next the stage that may persist
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public PseudonymisingSink(RunBundlePseudonymiser pseudonymiser, PseudonymisedRunSink next) {
    this.pseudonymiser = Objects.requireNonNull(pseudonymiser, "pseudonymiser");
    this.next = Objects.requireNonNull(next, "next");
  }

  @Override
  public void accept(NormalisedRunBundle bundle) {
    next.accept(pseudonymiser.pseudonymise(bundle));
  }
}
