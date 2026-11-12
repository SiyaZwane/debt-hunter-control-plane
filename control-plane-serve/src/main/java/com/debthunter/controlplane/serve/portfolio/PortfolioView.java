package com.debthunter.controlplane.serve.portfolio;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The estate by team. There is deliberately no repository name anywhere in it and no way to ask for
 * a finer grouping: a leader sees how each team is doing, not which repository or person is behind
 * a number.
 *
 * @param generatedAt when this was produced
 * @param readModelsAsOf when the read models were last brought up to date, or null if never
 * @param stale whether they are older than the staleness threshold; if true the numbers are old
 * @param coverageAsOf when coverage was last reconciled, or null if never
 * @param teams one row per team, by name
 */
public record PortfolioView(
    Instant generatedAt,
    Instant readModelsAsOf,
    boolean stale,
    Instant coverageAsOf,
    List<TeamRow> teams) {

  /** Copies the teams. */
  public PortfolioView {
    teams = List.copyOf(teams);
  }

  /**
   * One team.
   *
   * @param team the owning team
   * @param repositories how many repositories it owns
   * @param findingsBySeverity findings in each team repository's latest run, by severity
   * @param repositoriesFailingPolicy repositories whose latest run failed policy
   * @param coverage repositories by coverage state
   * @param stages repositories by rollout stage ({@code NOT_IN_ROLLOUT} if none)
   */
  public record TeamRow(
      String team,
      int repositories,
      Map<String, Integer> findingsBySeverity,
      int repositoriesFailingPolicy,
      Map<String, Integer> coverage,
      Map<String, Integer> stages) {

    /** Copies the maps. */
    public TeamRow {
      findingsBySeverity = Map.copyOf(findingsBySeverity);
      coverage = Map.copyOf(coverage);
      stages = Map.copyOf(stages);
    }
  }
}
