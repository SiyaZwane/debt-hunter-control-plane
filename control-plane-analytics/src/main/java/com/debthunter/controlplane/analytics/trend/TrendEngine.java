package com.debthunter.controlplane.analytics.trend;

import com.debthunter.controlplane.analytics.readmodel.HotspotPoint;
import com.debthunter.controlplane.analytics.readmodel.ReadModelRepository;
import com.debthunter.controlplane.analytics.readmodel.RuleDelta;
import com.debthunter.controlplane.analytics.readmodel.RunSnapshot;
import com.debthunter.controlplane.analytics.readmodel.Staleness;
import com.debthunter.controlplane.analytics.readmodel.Trended;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Per-repository trends, read from the materialised read models:
 *
 * <ul>
 *   <li>finding count by severity over time,
 *   <li>the rate at which findings are created, resolved, and regress,
 *   <li>hotspot movement, and
 *   <li>the complexity trajectory.
 * </ul>
 *
 * <p>Every result carries a {@code stale} flag. Windows are half-open, {@code [from, to)}.
 */
public final class TrendEngine {

  /** How a hotspot's change frequency moved across a window. */
  public enum Direction {
    /** It is changing more often than it was. */
    RISING,
    /** It is changing less often. */
    FALLING,
    /** No movement, or only one observation. */
    STEADY
  }

  /**
   * Findings by severity at one commit.
   *
   * @param committedAt the commit date
   * @param commit the commit
   * @param critical critical findings
   * @param high high findings
   * @param medium medium findings
   * @param low low findings
   * @param info informational findings
   * @param total all findings
   */
  public record SeverityPoint(
      Instant committedAt,
      String commit,
      int critical,
      int high,
      int medium,
      int low,
      int info,
      int total) {}

  /**
   * Findings created, resolved and regressed on one day.
   *
   * @param day the UTC day
   * @param created findings first observed
   * @param resolved findings resolved
   * @param regressed findings that returned
   */
  public record DailyRate(LocalDate day, int created, int resolved, int regressed) {}

  /**
   * How fast findings are being created, resolved and regressing over a window.
   *
   * @param created total created
   * @param resolved total resolved
   * @param regressed total regressed
   * @param days the window's length in days
   * @param createdPerDay mean created per day
   * @param resolvedPerDay mean resolved per day
   * @param regressedPerDay mean regressed per day
   * @param regressionRate regressed divided by resolved, or {@code null} if nothing was resolved
   * @param daily the per-day series, only days with any activity
   */
  public record FindingRates(
      int created,
      int resolved,
      int regressed,
      long days,
      double createdPerDay,
      double resolvedPerDay,
      double regressedPerDay,
      Double regressionRate,
      List<DailyRate> daily) {

    /** Defensively copies the series. */
    public FindingRates {
      daily = List.copyOf(daily);
    }
  }

  /**
   * How one file's change frequency moved.
   *
   * @param path the file
   * @param first its change frequency at the first observation in the window
   * @param last its change frequency at the last observation in the window
   * @param delta {@code last - first}
   * @param direction whether it rose, fell or held
   * @param observations how many commits it was observed at
   */
  public record HotspotMovement(
      String path,
      double first,
      double last,
      double delta,
      Direction direction,
      int observations) {}

  /**
   * Complexity change at one commit and cumulatively across the window.
   *
   * @param committedAt the commit date
   * @param commit the commit
   * @param delta the complexity change reported at this commit
   * @param cumulative the running total from the start of the window
   */
  public record ComplexityPoint(
      Instant committedAt, String commit, double delta, double cumulative) {}

  private final ReadModelRepository readModel;
  private final Staleness staleness;

  /**
   * Creates the engine.
   *
   * @param readModel the read models
   * @param staleness decides and reports freshness
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public TrendEngine(ReadModelRepository readModel, Staleness staleness) {
    this.readModel = Objects.requireNonNull(readModel, "readModel");
    this.staleness = Objects.requireNonNull(staleness, "staleness");
  }

  /** Finding count by severity at each commit in the window, oldest first. */
  public Trended<List<SeverityPoint>> severityOverTime(
      String repository, String projectSlice, Instant from, Instant to) {
    List<SeverityPoint> points =
        readModel.snapshots(repository, projectSlice, from, to).stream()
            .map(
                s ->
                    new SeverityPoint(
                        s.committedAt(),
                        s.commit(),
                        s.critical(),
                        s.high(),
                        s.medium(),
                        s.low(),
                        s.info(),
                        s.total()))
            .toList();
    return staleness.wrap(points);
  }

  /** How fast findings are created, resolved, and regress over the window. */
  public Trended<FindingRates> findingRates(
      String repository, String projectSlice, Instant from, Instant to) {
    List<RuleDelta> deltas = readModel.ruleDeltas(repository, projectSlice, from, to);
    Map<LocalDate, int[]> byDay = new TreeMap<>();
    int created = 0;
    int resolved = 0;
    int regressed = 0;
    for (RuleDelta d : deltas) {
      int[] day = byDay.computeIfAbsent(d.day(), k -> new int[3]);
      day[0] += d.created();
      day[1] += d.resolved();
      day[2] += d.regressed();
      created += d.created();
      resolved += d.resolved();
      regressed += d.regressed();
    }
    long days =
        Math.max(
            1,
            ChronoUnit.DAYS.between(
                from.atZone(ZoneOffset.UTC).toLocalDate(),
                to.atZone(ZoneOffset.UTC).toLocalDate()));
    List<DailyRate> daily = new ArrayList<>();
    byDay.forEach((day, c) -> daily.add(new DailyRate(day, c[0], c[1], c[2])));
    return staleness.wrap(
        new FindingRates(
            created,
            resolved,
            regressed,
            days,
            (double) created / days,
            (double) resolved / days,
            (double) regressed / days,
            resolved == 0 ? null : (double) regressed / resolved,
            daily));
  }

  /**
   * Which hotspots are changing more or less often, most rising first.
   *
   * @param limit the most files to return
   */
  public Trended<List<HotspotMovement>> hotspotMovement(
      String repository, String projectSlice, Instant from, Instant to, int limit) {
    Map<String, List<HotspotPoint>> byPath =
        readModel.hotspots(repository, projectSlice, from, to).stream()
            .collect(Collectors.groupingBy(HotspotPoint::path, TreeMap::new, Collectors.toList()));
    List<HotspotMovement> movements = new ArrayList<>();
    byPath.forEach(
        (path, points) -> {
          points.sort(Comparator.comparing(HotspotPoint::committedAt));
          double first = points.get(0).changeFrequency();
          double last = points.get(points.size() - 1).changeFrequency();
          double delta = last - first;
          Direction direction =
              points.size() < 2 || delta == 0
                  ? Direction.STEADY
                  : delta > 0 ? Direction.RISING : Direction.FALLING;
          movements.add(new HotspotMovement(path, first, last, delta, direction, points.size()));
        });
    movements.sort(
        Comparator.comparingDouble(HotspotMovement::delta)
            .reversed()
            .thenComparing(HotspotMovement::path));
    return staleness.wrap(movements.stream().limit(limit).toList());
  }

  /** The complexity change at each commit and its running total, oldest first. */
  public Trended<List<ComplexityPoint>> complexityTrajectory(
      String repository, String projectSlice, Instant from, Instant to) {
    List<ComplexityPoint> points = new ArrayList<>();
    double running = 0;
    for (RunSnapshot s : readModel.snapshots(repository, projectSlice, from, to)) {
      running += s.complexityDelta();
      points.add(new ComplexityPoint(s.committedAt(), s.commit(), s.complexityDelta(), running));
    }
    return staleness.wrap(points);
  }
}
