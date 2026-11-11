package com.debthunter.controlplane.governance.simulation;

import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.findings.PseudonymisedFindingStore;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistry;
import com.debthunter.controlplane.store.runs.RunRecord;
import com.debthunter.controlplane.store.runs.RunRepository;
import com.debthunter.controlplane.store.suppressions.SuppressionLookup;
import java.time.Instant;
import java.util.List;

/**
 * {@link RetainedEvidence} over the live system of record. The only class in governance that
 * touches the stores, and an architecture test holds it to read methods.
 */
public final class StoreBackedRetainedEvidence implements RetainedEvidence {

  private final RepositoryRegistry registry;
  private final RunRepository runs;
  private final PseudonymisedFindingStore findings;
  private final SuppressionLookup suppressions;

  /**
   * Creates the adapter.
   *
   * @param registry the onboarded repositories
   * @param runs the run store
   * @param findings the pseudonymised finding store
   * @param suppressions the suppression lookup
   */
  public StoreBackedRetainedEvidence(
      RepositoryRegistry registry,
      RunRepository runs,
      PseudonymisedFindingStore findings,
      SuppressionLookup suppressions) {
    this.registry = registry;
    this.runs = runs;
    this.findings = findings;
    this.suppressions = suppressions;
  }

  @Override
  public List<OnboardedRepository> estate() {
    return registry.all();
  }

  @Override
  public List<RunRecord> recentRuns(String repository, int limit) {
    return runs.recentForRepository(repository, limit);
  }

  @Override
  public List<PseudonymisedFinding> findingsOf(String runId) {
    return findings.findingsForRun(runId);
  }

  @Override
  public boolean suppressedAt(String repository, String fingerprint, Instant at) {
    return suppressions
        .find(repository, fingerprint)
        .map(active -> active.appliesAt(at))
        .orElse(false);
  }
}
