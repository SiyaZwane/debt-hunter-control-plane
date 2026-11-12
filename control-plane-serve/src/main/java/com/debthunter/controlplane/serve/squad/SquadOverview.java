package com.debthunter.controlplane.serve.squad;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * A squad's repositories at a glance.
 *
 * @param squad the squad
 * @param generatedAt when this was produced
 * @param asOf when the newest run the data reflects was received, or null if there is none
 * @param repositories one line per repository the squad owns
 */
public record SquadOverview(
    String squad, Instant generatedAt, Instant asOf, List<RepositoryLine> repositories) {

  /** Copies the repositories. */
  public SquadOverview {
    repositories = List.copyOf(repositories);
  }

  /**
   * One repository.
   *
   * @param repository the repository
   * @param tier its criticality tier
   * @param stage its rollout stage ({@code NOT_IN_ROLLOUT} if none)
   * @param coverage its coverage state, or null if coverage has not been reconciled
   * @param openBySeverity findings that are new, open or regressed, by severity
   */
  public record RepositoryLine(
      String repository,
      String tier,
      String stage,
      String coverage,
      Map<String, Integer> openBySeverity) {

    /** Copies the map. */
    public RepositoryLine {
      openBySeverity = Map.copyOf(openBySeverity);
    }
  }
}
