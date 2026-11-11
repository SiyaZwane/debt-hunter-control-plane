package com.debthunter.controlplane.analytics.coverage;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The result of reconciling the registry against the source-control estate.
 *
 * <p>Every repository in the estate <em>or</em> the registry appears exactly once.
 *
 * @param reconciledAt when the reconciliation ran
 * @param estateAvailable whether the estate was consulted; if not, repositories that were never
 *     onboarded cannot be found and the report says so rather than implying there are none
 * @param repositories each repository's coverage, ordered by identifier
 * @param warnings things a reader must know to interpret the report
 */
public record CoverageReport(
    Instant reconciledAt,
    boolean estateAvailable,
    List<RepositoryCoverage> repositories,
    List<String> warnings) {

  /** Validates and defensively copies the components. */
  public CoverageReport {
    Objects.requireNonNull(reconciledAt, "reconciledAt");
    repositories = List.copyOf(repositories);
    warnings = List.copyOf(warnings);
  }

  /** The repositories in a given state. */
  public List<RepositoryCoverage> inState(CoverageState state) {
    return repositories.stream().filter(r -> r.state() == state).toList();
  }

  /**
   * The repositories that are covered with trustworthy, current data. A repository that was never
   * onboarded is <strong>not</strong> in here: having no findings because nobody is looking is not
   * the same as being clean.
   */
  public List<RepositoryCoverage> healthy() {
    return repositories.stream().filter(r -> r.state().isHealthy()).toList();
  }

  /** The repositories that are not covered with trustworthy, current data. */
  public List<RepositoryCoverage> uncovered() {
    return repositories.stream().filter(r -> !r.state().isHealthy()).toList();
  }

  /** Counts per state, with every state present (zero if none). */
  public Map<CoverageState, Integer> countsByState() {
    Map<CoverageState, Integer> counts = new EnumMap<>(CoverageState.class);
    for (CoverageState state : CoverageState.values()) {
      counts.put(state, 0);
    }
    repositories.forEach(r -> counts.merge(r.state(), 1, Integer::sum));
    return counts;
  }

  /** The share of known repositories that are covered, or {@code null} if there are none. */
  public Double healthyShare() {
    return repositories.isEmpty() ? null : (double) healthy().size() / repositories.size();
  }
}
