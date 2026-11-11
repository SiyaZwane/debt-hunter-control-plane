package com.debthunter.controlplane.ingest.pseudonymise;

import com.debthunter.controlplane.store.findings.PseudonymisedFindingStore;
import java.util.Objects;

/** Persists a pseudonymised bundle's findings. */
public final class FindingStoreSink implements PseudonymisedRunSink {

  private final PseudonymisedFindingStore store;

  /**
   * Creates the sink.
   *
   * @param store where findings are kept
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public FindingStoreSink(PseudonymisedFindingStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  @Override
  public void accept(PseudonymisedRunBundle bundle) {
    store.saveRun(bundle.run().id(), bundle.run().repository(), bundle.findings());
  }
}
