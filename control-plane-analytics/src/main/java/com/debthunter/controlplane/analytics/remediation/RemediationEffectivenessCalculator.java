package com.debthunter.controlplane.analytics.remediation;

import com.debthunter.controlplane.analytics.readmodel.ReadModelRepository;
import com.debthunter.controlplane.analytics.readmodel.ResolutionRecord;
import com.debthunter.controlplane.analytics.readmodel.RuleDelta;
import com.debthunter.controlplane.analytics.readmodel.RunSnapshot;
import com.debthunter.controlplane.analytics.readmodel.Staleness;
import com.debthunter.controlplane.analytics.readmodel.Trended;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * How effectively findings are being remediated: how long fixing takes, which rules keep coming
 * back, and how often policy would have failed.
 */
public final class RemediationEffectivenessCalculator {

  /**
   * How often findings of one rule came back after being resolved.
   *
   * @param ruleId the rule
   * @param resolved findings of this rule resolved in the window
   * @param regressed findings of this rule that regressed in the window
   * @param rate regressed divided by resolved, or {@code null} if nothing was resolved
   */
  public record RuleRegression(String ruleId, int resolved, int regressed, Double rate) {}

  /**
   * How much pressure policy puts on a repository.
   *
   * @param runs runs in the window
   * @param failing runs whose policy outcome was {@code FAILED}
   * @param wouldFail runs whose policy outcome was {@code WOULD_FAIL} (observe-only)
   * @param failingShare {@code (failing + wouldFail) / runs}, or {@code null} with no runs
   */
  public record PolicyPressure(int runs, int failing, int wouldFail, Double failingShare) {}

  /**
   * The remediation picture for a window.
   *
   * @param resolvedCount findings resolved
   * @param medianTimeToResolution the median time a resolved finding had been open, or {@code null}
   *     if none were resolved
   * @param medianBySeverity the same, per severity
   * @param regressionByRule regression rate per rule, rules with most regressions first
   * @param policyPressure how often policy failed or would have
   */
  public record RemediationReport(
      int resolvedCount,
      Duration medianTimeToResolution,
      Map<String, Duration> medianBySeverity,
      List<RuleRegression> regressionByRule,
      PolicyPressure policyPressure) {

    /** Defensively copies the collections. */
    public RemediationReport {
      medianBySeverity = Map.copyOf(medianBySeverity);
      regressionByRule = List.copyOf(regressionByRule);
    }
  }

  private final ReadModelRepository readModel;
  private final Staleness staleness;

  /**
   * Creates the calculator.
   *
   * @param readModel the read models
   * @param staleness decides and reports freshness
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public RemediationEffectivenessCalculator(ReadModelRepository readModel, Staleness staleness) {
    this.readModel = Objects.requireNonNull(readModel, "readModel");
    this.staleness = Objects.requireNonNull(staleness, "staleness");
  }

  /**
   * Measures remediation over a window.
   *
   * @param repository the repository
   * @param projectSlice the project slice
   * @param from the window start
   * @param to the window end
   * @return the report
   */
  public Trended<RemediationReport> report(
      String repository, String projectSlice, Instant from, Instant to) {
    List<ResolutionRecord> resolutions = readModel.resolutions(repository, projectSlice, from, to);
    Map<String, Duration> bySeverity = new TreeMap<>();
    resolutions.stream()
        .collect(Collectors.groupingBy(ResolutionRecord::severity))
        .forEach((severity, rows) -> bySeverity.put(severity, median(durations(rows))));

    return staleness.wrap(
        new RemediationReport(
            resolutions.size(),
            resolutions.isEmpty() ? null : median(durations(resolutions)),
            bySeverity,
            regressionByRule(repository, projectSlice, from, to),
            policyPressure(readModel.snapshots(repository, projectSlice, from, to))));
  }

  private List<RuleRegression> regressionByRule(
      String repository, String projectSlice, Instant from, Instant to) {
    Map<String, int[]> byRule = new TreeMap<>();
    for (RuleDelta d : readModel.ruleDeltas(repository, projectSlice, from, to)) {
      int[] c = byRule.computeIfAbsent(d.ruleId(), k -> new int[2]);
      c[0] += d.resolved();
      c[1] += d.regressed();
    }
    List<RuleRegression> result = new ArrayList<>();
    byRule.forEach(
        (rule, c) ->
            result.add(
                new RuleRegression(rule, c[0], c[1], c[0] == 0 ? null : (double) c[1] / c[0])));
    result.sort(
        Comparator.comparingInt(RuleRegression::regressed)
            .reversed()
            .thenComparing(RuleRegression::ruleId));
    return result;
  }

  private static PolicyPressure policyPressure(List<RunSnapshot> snapshots) {
    int failing = (int) snapshots.stream().filter(s -> "FAILED".equals(s.policyStatus())).count();
    int wouldFail =
        (int) snapshots.stream().filter(s -> "WOULD_FAIL".equals(s.policyStatus())).count();
    return new PolicyPressure(
        snapshots.size(),
        failing,
        wouldFail,
        snapshots.isEmpty() ? null : (double) (failing + wouldFail) / snapshots.size());
  }

  private static List<Long> durations(List<ResolutionRecord> rows) {
    return rows.stream().map(ResolutionRecord::durationSeconds).sorted().toList();
  }

  private static Duration median(List<Long> sortedSeconds) {
    int n = sortedSeconds.size();
    long seconds =
        n % 2 == 1
            ? sortedSeconds.get(n / 2)
            : (sortedSeconds.get(n / 2 - 1) + sortedSeconds.get(n / 2)) / 2;
    return Duration.ofSeconds(seconds);
  }
}
