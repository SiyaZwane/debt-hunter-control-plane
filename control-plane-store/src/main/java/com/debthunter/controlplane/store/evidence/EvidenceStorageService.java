package com.debthunter.controlplane.store.evidence;

import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Keeps the detail behind findings in a hot tier for a configurable time, then reduces it to counts
 * in a warm tier.
 *
 * <p>Only identity-free evidence can enter: snapshots are captured from {@link
 * PseudonymisedFinding}s. Expiry is judged against a date the caller supplies, so a replay of
 * history expires the same evidence the original did.
 */
public final class EvidenceStorageService {

  private final EvidenceRepository evidence;
  private final Duration hotTtl;

  /**
   * Creates the service.
   *
   * @param evidence where the tiers are kept
   * @param hotTtl how long full detail stays in the hot tier
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public EvidenceStorageService(EvidenceRepository evidence, Duration hotTtl) {
    this.evidence = Objects.requireNonNull(evidence, "evidence");
    this.hotTtl = Objects.requireNonNull(hotTtl, "hotTtl");
    if (hotTtl.isNegative() || hotTtl.isZero()) {
      throw new IllegalArgumentException("The hot-tier TTL must be positive");
    }
  }

  /**
   * Stores the evidence of every finding in a run that has any.
   *
   * @param runId the run
   * @param repository the repository
   * @param findings the run's pseudonymised findings
   * @param capturedAt when (the commit date)
   * @return how many snapshots were stored
   */
  public int store(
      String runId, String repository, List<PseudonymisedFinding> findings, Instant capturedAt) {
    int stored = 0;
    for (PseudonymisedFinding finding : findings) {
      if (!finding.evidence().isEmpty()) {
        evidence.saveHot(EvidenceSnapshot.capture(runId, repository, finding, capturedAt, hotTtl));
        stored++;
      }
    }
    return stored;
  }

  /**
   * Moves expired detail to the warm tier.
   *
   * @param asOf the date to judge expiry against
   * @return how many snapshots moved
   */
  public int expire(Instant asOf) {
    return evidence.moveExpiredToWarm(asOf);
  }

  /** The full-detail snapshots captured in a run that have not yet expired. */
  public List<EvidenceSnapshot> hotFor(String runId) {
    return evidence.hotForRun(runId);
  }

  /** The counts left behind for a repository. */
  public List<EvidenceSummary> warmSummaryFor(String repository) {
    return evidence.warmSummaries(repository);
  }
}
