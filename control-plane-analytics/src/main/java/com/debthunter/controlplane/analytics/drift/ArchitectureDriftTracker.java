package com.debthunter.controlplane.analytics.drift;

import com.debthunter.controlplane.analytics.readmodel.OpenFinding;
import com.debthunter.controlplane.analytics.readmodel.ReadModelRepository;
import com.debthunter.controlplane.analytics.readmodel.Staleness;
import com.debthunter.controlplane.analytics.readmodel.Trended;
import com.debthunter.domain.Category;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Tracks how architecture conformance is changing: whether violations are accumulating, how fast,
 * for how long they have been accumulating, and how long the standing ones have been open.
 */
public final class ArchitectureDriftTracker {

  /**
   * Architecture violations at one commit.
   *
   * @param committedAt the commit date
   * @param commit the commit
   * @param violations violations at that commit
   */
  public record ViolationPoint(Instant committedAt, String commit, int violations) {}

  /**
   * How architecture conformance has moved over a window.
   *
   * @param series violations at each commit, oldest first
   * @param violationsPerWeek net change in violations per week across the window; positive means
   *     drifting away from the architecture
   * @param driftingFor how long violations have been uninterruptedly non-decreasing and net up,
   *     ending at the latest commit; {@link Duration#ZERO} if they are not drifting
   * @param openViolations architecture violations open now
   * @param medianOpenAge the median age of the open ones as of the asked date
   * @param longestOpenAge the age of the oldest
   */
  public record ArchitectureDrift(
      List<ViolationPoint> series,
      double violationsPerWeek,
      Duration driftingFor,
      int openViolations,
      Duration medianOpenAge,
      Duration longestOpenAge) {

    /** Defensively copies the series. */
    public ArchitectureDrift {
      series = List.copyOf(series);
    }
  }

  private final ReadModelRepository readModel;
  private final Staleness staleness;

  /**
   * Creates the tracker.
   *
   * @param readModel the read models
   * @param staleness decides and reports freshness
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public ArchitectureDriftTracker(ReadModelRepository readModel, Staleness staleness) {
    this.readModel = Objects.requireNonNull(readModel, "readModel");
    this.staleness = Objects.requireNonNull(staleness, "staleness");
  }

  /**
   * Measures drift.
   *
   * @param repository the repository
   * @param projectSlice the project slice
   * @param from the window start
   * @param to the window end
   * @param asOf the date open violations are aged against (a commit date, not the wall clock, when
   *     replaying history)
   * @return the drift
   */
  public Trended<ArchitectureDrift> drift(
      String repository, String projectSlice, Instant from, Instant to, Instant asOf) {
    List<ViolationPoint> series =
        readModel.snapshots(repository, projectSlice, from, to).stream()
            .map(s -> new ViolationPoint(s.committedAt(), s.commit(), s.architectureViolations()))
            .toList();

    List<Duration> ages =
        readModel.openFindings(repository, projectSlice).stream()
            .filter(f -> f.category().equals(Category.ARCHITECTURE.name()))
            .map(OpenFinding::since)
            .map(
                since ->
                    Duration.between(since, asOf).isNegative()
                        ? Duration.ZERO
                        : Duration.between(since, asOf))
            .sorted()
            .toList();

    return staleness.wrap(
        new ArchitectureDrift(
            series,
            perWeek(series),
            driftingFor(series),
            ages.size(),
            median(ages),
            ages.isEmpty() ? Duration.ZERO : ages.get(ages.size() - 1)));
  }

  private static double perWeek(List<ViolationPoint> series) {
    if (series.size() < 2) {
      return 0;
    }
    ViolationPoint first = series.get(0);
    ViolationPoint last = series.get(series.size() - 1);
    double days = Duration.between(first.committedAt(), last.committedAt()).toSeconds() / 86_400.0;
    return days <= 0 ? 0 : (last.violations() - first.violations()) / days * 7.0;
  }

  /** Walks back from the latest commit while violations never fell; drifting if they ended up. */
  private static Duration driftingFor(List<ViolationPoint> series) {
    if (series.size() < 2) {
      return Duration.ZERO;
    }
    int start = series.size() - 1;
    while (start > 0 && series.get(start - 1).violations() <= series.get(start).violations()) {
      start--;
    }
    ViolationPoint from = series.get(start);
    ViolationPoint last = series.get(series.size() - 1);
    return last.violations() > from.violations()
        ? Duration.between(from.committedAt(), last.committedAt())
        : Duration.ZERO;
  }

  private static Duration median(List<Duration> sorted) {
    if (sorted.isEmpty()) {
      return Duration.ZERO;
    }
    int n = sorted.size();
    return n % 2 == 1
        ? sorted.get(n / 2)
        : sorted.get(n / 2 - 1).plus(sorted.get(n / 2)).dividedBy(2);
  }
}
