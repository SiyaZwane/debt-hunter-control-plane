package com.debthunter.controlplane.analytics.coverage;

import com.debthunter.controlplane.store.rollout.RolloutStage;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * One repository's coverage.
 *
 * @param repository the repository identifier
 * @param state the one state that applies
 * @param reasons everything that applies, in words, including conditions the state outranks
 * @param lastRunReceivedAt when the latest run was received, or {@code null} if none ever was
 * @param lastToolVersion the tool version of the latest run, or {@code null}
 * @param onboarded whether it is in the registry
 * @param inEstate whether it is in the source-control estate, or {@code null} if the estate could
 *     not be consulted
 * @param rolloutStage where the repository is in the rollout, or {@code null} if it is not in the
 *     rollout (or the stage could not be read, which the report's warnings say)
 */
public record RepositoryCoverage(
    String repository,
    CoverageState state,
    List<String> reasons,
    Instant lastRunReceivedAt,
    String lastToolVersion,
    boolean onboarded,
    Boolean inEstate,
    RolloutStage rolloutStage) {

  /** A coverage entry with no rollout stage. */
  public RepositoryCoverage(
      String repository,
      CoverageState state,
      List<String> reasons,
      Instant lastRunReceivedAt,
      String lastToolVersion,
      boolean onboarded,
      Boolean inEstate) {
    this(repository, state, reasons, lastRunReceivedAt, lastToolVersion, onboarded, inEstate, null);
  }

  /** A copy showing a rollout stage. */
  public RepositoryCoverage withRolloutStage(RolloutStage stage) {
    return new RepositoryCoverage(
        repository, state, reasons, lastRunReceivedAt, lastToolVersion, onboarded, inEstate, stage);
  }

  /** Validates and defensively copies the components. */
  public RepositoryCoverage {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(state, "state");
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
  }
}
