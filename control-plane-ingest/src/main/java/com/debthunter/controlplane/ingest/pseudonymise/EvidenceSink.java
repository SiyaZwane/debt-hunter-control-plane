package com.debthunter.controlplane.ingest.pseudonymise;

import com.debthunter.controlplane.store.evidence.EvidenceStorageService;
import java.util.Objects;

/** Stores a pseudonymised bundle's finding evidence in the hot tier. */
public final class EvidenceSink implements PseudonymisedRunSink {

  private final EvidenceStorageService evidence;

  /**
   * Creates the sink.
   *
   * @param evidence the evidence tiers
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public EvidenceSink(EvidenceStorageService evidence) {
    this.evidence = Objects.requireNonNull(evidence, "evidence");
  }

  @Override
  public void accept(PseudonymisedRunBundle bundle) {
    evidence.store(
        bundle.run().id(), bundle.run().repository(), bundle.findings(), bundle.run().timestamp());
  }
}
