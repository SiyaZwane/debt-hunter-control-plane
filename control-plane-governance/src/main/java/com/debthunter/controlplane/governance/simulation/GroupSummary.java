package com.debthunter.controlplane.governance.simulation;

/**
 * Counts for a slice of the estate. A {@code null} {@code tier} or {@code stage} means "all".
 *
 * <p>Every repository in the slice is in exactly one of {@code simulated} or {@code notSimulated};
 * the pass and fail counts describe only the simulated ones, and are not to be read as the slice's
 * totals unless {@code notSimulated} is zero.
 *
 * @param tier the criticality tier, or {@code null} for all tiers
 * @param stage the rollout stage, or {@code null} for all stages
 * @param repositories how many repositories are in the slice
 * @param simulated how many could be simulated
 * @param notSimulated how many could not
 * @param passingUnderCandidate simulated repositories that would pass
 * @param failingUnderCandidate simulated repositories that would fail
 * @param newlyFailing repositories that pass today and would fail
 * @param newlyPassing repositories that fail today and would pass
 * @param deltaUnknown simulated repositories with nothing to compare against
 */
public record GroupSummary(
    String tier,
    String stage,
    int repositories,
    int simulated,
    int notSimulated,
    int passingUnderCandidate,
    int failingUnderCandidate,
    int newlyFailing,
    int newlyPassing,
    int deltaUnknown) {

  /**
   * The proportion of the slice that could not be simulated.
   *
   * @return a value from 0.0 to 1.0; 0.0 for an empty slice
   */
  public double notSimulatedShare() {
    return repositories == 0 ? 0.0 : (double) notSimulated / repositories;
  }
}
