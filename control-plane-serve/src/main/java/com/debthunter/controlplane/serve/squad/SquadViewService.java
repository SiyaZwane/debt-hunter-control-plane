package com.debthunter.controlplane.serve.squad;

import com.debthunter.controlplane.analytics.coverage.CoverageRepository;
import com.debthunter.controlplane.analytics.coverage.RepositoryCoverage;
import com.debthunter.controlplane.serve.access.Access;
import com.debthunter.controlplane.serve.access.AccessDeniedException;
import com.debthunter.controlplane.serve.access.Caller;
import com.debthunter.controlplane.serve.access.NotFoundException;
import com.debthunter.controlplane.serve.access.Roles;
import com.debthunter.controlplane.serve.finding.FindingDetailService;
import com.debthunter.controlplane.serve.finding.FindingDetailView;
import com.debthunter.controlplane.serve.query.FindingQueryRepository;
import com.debthunter.controlplane.serve.query.Page;
import com.debthunter.controlplane.serve.query.QueryParams;
import com.debthunter.controlplane.serve.query.QueryResult;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistry;
import com.debthunter.controlplane.store.rollout.RolloutStage;
import com.debthunter.controlplane.store.rollout.StageLookup;
import com.debthunter.domain.Severity;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * A squad's own view: its repositories and their findings, each finding leading on to its detail
 * and its evidence. Everything is limited to the squads named in the caller's token. A squad that
 * is not the caller's, or a repository outside it, is refused with the same answer whether or not
 * it exists.
 */
public final class SquadViewService {

  private final RepositoryRegistry registry;
  private final FindingQueryRepository findings;
  private final CoverageRepository coverage;
  private final StageLookup stages;
  private final FindingDetailService details;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param registry which squad owns which repository
   * @param findings where findings are read from
   * @param coverage the latest coverage report
   * @param stages the rollout stages
   * @param details a finding with its explanation
   * @param clock the source of "now"
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public SquadViewService(
      RepositoryRegistry registry,
      FindingQueryRepository findings,
      CoverageRepository coverage,
      StageLookup stages,
      FindingDetailService details,
      Clock clock) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.findings = Objects.requireNonNull(findings, "findings");
    this.coverage = Objects.requireNonNull(coverage, "coverage");
    this.stages = Objects.requireNonNull(stages, "stages");
    this.details = Objects.requireNonNull(details, "details");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * The squad's repositories at a glance.
   *
   * @param caller who is asking
   * @param squad the squad
   * @return the overview
   * @throws AccessDeniedException if the caller lacks the squad role or is not in that squad
   */
  public SquadOverview overview(Caller caller, String squad) {
    Access.requireSquad(caller, Roles.SQUAD, squad);
    List<OnboardedRepository> owned = ownedBy(squad);
    Set<String> names =
        owned.stream().map(OnboardedRepository::repository).collect(Collectors.toSet());
    Map<String, Map<Severity, Integer>> open =
        names.isEmpty() ? Map.of() : findings.openCounts(names);
    Map<String, RolloutStage> currentStages = stages.currentStages();
    Map<String, RepositoryCoverage> covered = new HashMap<>();
    coverage.latest().ifPresent(r -> r.repositories().forEach(c -> covered.put(c.repository(), c)));

    List<SquadOverview.RepositoryLine> lines = new ArrayList<>();
    for (OnboardedRepository r : owned) {
      Map<String, Integer> bySeverity = new TreeMap<>();
      open.getOrDefault(r.repository(), Map.of()).forEach((s, n) -> bySeverity.put(s.name(), n));
      RolloutStage stage = currentStages.get(r.repository());
      RepositoryCoverage c = covered.get(r.repository());
      lines.add(
          new SquadOverview.RepositoryLine(
              r.repository(),
              r.tier(),
              stage == null ? "NOT_IN_ROLLOUT" : stage.name(),
              c == null ? null : c.state().name(),
              bySeverity));
    }
    return new SquadOverview(squad, clock.instant(), findings.asOf().orElse(null), lines);
  }

  /**
   * The squad's findings.
   *
   * @param caller who is asking
   * @param squad the squad
   * @param params the filters and page
   * @return a page of findings, each carrying the run whose evidence supports it
   * @throws AccessDeniedException if the caller is not in that squad or names a repository outside
   *     it
   */
  public QueryResult findings(Caller caller, String squad, QueryParams params) {
    Access.requireSquad(caller, Roles.SQUAD, squad);
    Set<String> names = repositoriesOf(squad, params.repository());
    if (names.isEmpty()) {
      return new QueryResult(List.of(), null, findings.asOf().orElse(null), clock.instant());
    }
    Page<com.debthunter.controlplane.serve.query.FindingRow> page =
        findings.find(params.toFilter(names), params.page());
    return new QueryResult(
        page.items(), page.nextCursor(), findings.asOf().orElse(null), clock.instant());
  }

  /**
   * One finding with its explanation.
   *
   * @param caller who is asking
   * @param squad the squad
   * @param repository the finding's repository
   * @param runId the run that reported it
   * @param findingId its id in that run
   * @return the finding and explanation
   * @throws AccessDeniedException if the caller is not in that squad or the repository is not its
   * @throws NotFoundException if the run or finding does not exist
   */
  public FindingDetailView detail(
      Caller caller, String squad, String repository, String runId, String findingId) {
    Access.requireSquad(caller, Roles.SQUAD, squad);
    repositoriesOf(squad, repository);
    return details
        .detail(repository, runId, findingId)
        .map(FindingDetailView::of)
        .orElseThrow(() -> new NotFoundException("There is no such finding"));
  }

  private Set<String> repositoriesOf(String squad, String only) {
    Set<String> names =
        ownedBy(squad).stream().map(OnboardedRepository::repository).collect(Collectors.toSet());
    if (only == null) {
      return names;
    }
    if (!names.contains(only)) {
      throw new AccessDeniedException("That repository does not belong to the squad");
    }
    return Set.of(only);
  }

  private List<OnboardedRepository> ownedBy(String squad) {
    return registry.all().stream().filter(r -> r.owner().equals(squad)).toList();
  }
}
