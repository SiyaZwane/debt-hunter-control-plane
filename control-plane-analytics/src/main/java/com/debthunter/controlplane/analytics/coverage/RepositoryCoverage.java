package com.debthunter.controlplane.analytics.coverage;

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
 */
public record RepositoryCoverage(
    String repository,
    CoverageState state,
    List<String> reasons,
    Instant lastRunReceivedAt,
    String lastToolVersion,
    boolean onboarded,
    Boolean inEstate) {

  /** Validates and defensively copies the components. */
  public RepositoryCoverage {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(state, "state");
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
  }
}
