package com.debthunter.controlplane.governance.simulation;

import com.debthunter.controlplane.governance.policy.ValidationReport;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The result of replaying a candidate policy against the estate's retained evidence.
 *
 * @param candidateVersion the candidate bundle's version
 * @param asOf the moment the simulation was run
 * @param lookback the window replayed
 * @param validation the candidate's validation, including any warnings
 * @param repositories one entry per onboarded repository, sorted by identifier
 * @param summary the estate-level roll-up
 */
public record ImpactReport(
    String candidateVersion,
    Instant asOf,
    Duration lookback,
    ValidationReport validation,
    List<RepositoryImpact> repositories,
    EstateSummary summary) {

  /** Defensively copies the repository list. */
  public ImpactReport {
    repositories = List.copyOf(repositories);
  }

  /**
   * The proportion of the estate that could not be simulated. When this is above zero the figures
   * in {@link #summary()} describe only the repositories that could be.
   *
   * @return a value from 0.0 to 1.0
   */
  public double notSimulatedShare() {
    return summary.overall().notSimulatedShare();
  }

  /**
   * Whether every repository was simulated.
   *
   * @return {@code true} if nothing was left out
   */
  public boolean isComplete() {
    return summary.overall().notSimulated() == 0;
  }
}
