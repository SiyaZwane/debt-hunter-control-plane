package com.debthunter.controlplane.serve.portfolio;

import com.debthunter.controlplane.analytics.coverage.CoverageReport;
import com.debthunter.controlplane.analytics.coverage.CoverageRepository;
import com.debthunter.controlplane.analytics.coverage.RepositoryCoverage;
import com.debthunter.controlplane.analytics.readmodel.ReadModelRepository;
import com.debthunter.controlplane.analytics.readmodel.RunSnapshot;
import com.debthunter.controlplane.analytics.readmodel.Staleness;
import com.debthunter.controlplane.analytics.readmodel.Trended;
import com.debthunter.controlplane.serve.access.Access;
import com.debthunter.controlplane.serve.access.Caller;
import com.debthunter.controlplane.serve.access.Roles;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistry;
import com.debthunter.controlplane.store.rollout.RolloutStage;
import com.debthunter.controlplane.store.rollout.StageLookup;
import com.debthunter.domain.Severity;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * The estate-level view for leaders: one row per team, built from the read models, coverage and the
 * rollout stages. It carries how old the read models are and says so when they are stale, rather
 * than presenting old numbers as current.
 */
public final class PortfolioDashboardService {

  private final RepositoryRegistry registry;
  private final ReadModelRepository readModel;
  private final CoverageRepository coverage;
  private final StageLookup stages;
  private final Staleness staleness;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param registry which team owns which repository
   * @param readModel the metric read models
   * @param coverage the latest coverage report
   * @param stages the rollout stages
   * @param staleness decides whether the read models are stale
   * @param clock the source of "now"
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public PortfolioDashboardService(
      RepositoryRegistry registry,
      ReadModelRepository readModel,
      CoverageRepository coverage,
      StageLookup stages,
      Staleness staleness,
      Clock clock) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.readModel = Objects.requireNonNull(readModel, "readModel");
    this.coverage = Objects.requireNonNull(coverage, "coverage");
    this.stages = Objects.requireNonNull(stages, "stages");
    this.staleness = Objects.requireNonNull(staleness, "staleness");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * The portfolio.
   *
   * @param caller who is asking
   * @return one row per team
   * @throws com.debthunter.controlplane.serve.access.AccessDeniedException if the caller is not a
   *     leader
   */
  public PortfolioView portfolio(Caller caller) {
    Access.requireRole(caller, Roles.LEADER);
    Map<String, Set<String>> byTeam = new TreeMap<>();
    for (OnboardedRepository r : registry.all()) {
      byTeam.computeIfAbsent(r.owner(), t -> new TreeSet<>()).add(r.repository());
    }
    Set<String> everything = new TreeSet<>();
    byTeam.values().forEach(everything::addAll);

    Trended<List<RunSnapshot>> snapshots = staleness.wrap(readModel.latestSnapshots(everything));
    Map<String, List<RunSnapshot>> snapshotsByRepository = new HashMap<>();
    for (RunSnapshot s : snapshots.value()) {
      snapshotsByRepository.computeIfAbsent(s.repository(), k -> new ArrayList<>()).add(s);
    }
    CoverageReport report = coverage.latest().orElse(null);
    Map<String, RepositoryCoverage> coverageByRepository = new HashMap<>();
    if (report != null) {
      report.repositories().forEach(c -> coverageByRepository.put(c.repository(), c));
    }
    Map<String, RolloutStage> currentStages = stages.currentStages();

    List<PortfolioView.TeamRow> rows = new ArrayList<>();
    for (Map.Entry<String, Set<String>> team : byTeam.entrySet()) {
      Map<String, Integer> bySeverity = new LinkedHashMap<>();
      for (Severity s : Severity.values()) {
        bySeverity.put(s.name(), 0);
      }
      Map<String, Integer> coverageCounts = new TreeMap<>();
      Map<String, Integer> stageCounts = new TreeMap<>();
      int failing = 0;
      for (String repository : team.getValue()) {
        boolean fails = false;
        for (RunSnapshot s : snapshotsByRepository.getOrDefault(repository, List.of())) {
          bySeverity.merge(Severity.CRITICAL.name(), s.critical(), Integer::sum);
          bySeverity.merge(Severity.HIGH.name(), s.high(), Integer::sum);
          bySeverity.merge(Severity.MEDIUM.name(), s.medium(), Integer::sum);
          bySeverity.merge(Severity.LOW.name(), s.low(), Integer::sum);
          bySeverity.merge(Severity.INFO.name(), s.info(), Integer::sum);
          fails |= "FAILED".equals(s.policyStatus());
        }
        failing += fails ? 1 : 0;
        RepositoryCoverage c = coverageByRepository.get(repository);
        coverageCounts.merge(c == null ? "UNKNOWN" : c.state().name(), 1, Integer::sum);
        RolloutStage stage = currentStages.get(repository);
        stageCounts.merge(stage == null ? "NOT_IN_ROLLOUT" : stage.name(), 1, Integer::sum);
      }
      rows.add(
          new PortfolioView.TeamRow(
              team.getKey(),
              team.getValue().size(),
              bySeverity,
              failing,
              coverageCounts,
              stageCounts));
    }
    return new PortfolioView(
        clock.instant(),
        snapshots.lastUpdated(),
        snapshots.stale(),
        report == null ? null : report.reconciledAt(),
        rows);
  }
}
