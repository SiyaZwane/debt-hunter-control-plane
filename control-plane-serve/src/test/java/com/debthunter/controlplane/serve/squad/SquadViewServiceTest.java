package com.debthunter.controlplane.serve.squad;

import static com.debthunter.controlplane.serve.ServeFixtures.CLOCK;
import static com.debthunter.controlplane.serve.ServeFixtures.GADGETS;
import static com.debthunter.controlplane.serve.ServeFixtures.NOW;
import static com.debthunter.controlplane.serve.ServeFixtures.THING;
import static com.debthunter.controlplane.serve.ServeFixtures.WIDGETS;
import static com.debthunter.controlplane.serve.ServeFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.analytics.coverage.CoverageReport;
import com.debthunter.controlplane.analytics.coverage.CoverageState;
import com.debthunter.controlplane.analytics.coverage.InMemoryCoverageRepository;
import com.debthunter.controlplane.analytics.coverage.RepositoryCoverage;
import com.debthunter.controlplane.serve.ServeFixtures;
import com.debthunter.controlplane.serve.access.AccessDeniedException;
import com.debthunter.controlplane.serve.access.NotFoundException;
import com.debthunter.controlplane.serve.finding.FindingDetailService;
import com.debthunter.controlplane.serve.finding.FindingFixture;
import com.debthunter.controlplane.serve.query.FindingRow;
import com.debthunter.controlplane.serve.query.InMemoryFindingQueryRepository;
import com.debthunter.controlplane.serve.query.QueryParams;
import com.debthunter.controlplane.serve.query.QueryResult;
import com.debthunter.controlplane.store.findings.InMemoryPseudonymisedFindingStore;
import com.debthunter.controlplane.store.rollout.RolloutStage;
import com.debthunter.controlplane.store.runs.InMemoryRunRepository;
import com.debthunter.domain.Severity;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-76 and CP-AC-77: a squad sees its own findings, linked to evidence; another squad gets 403.
 */
class SquadViewServiceTest {

  private final InMemoryFindingQueryRepository repo = new InMemoryFindingQueryRepository();
  private final InMemoryCoverageRepository coverage = new InMemoryCoverageRepository();
  private final InMemoryRunRepository runs = new InMemoryRunRepository();
  private final InMemoryPseudonymisedFindingStore store = new InMemoryPseudonymisedFindingStore();
  private final Map<String, RolloutStage> stages = new HashMap<>();
  private SquadViewService service;

  @BeforeEach
  void setUp() {
    service =
        new SquadViewService(
            ServeFixtures.registry(),
            repo,
            coverage,
            () -> stages,
            new FindingDetailService(runs, store, FindingFixture.templatesOnly()),
            CLOCK);
    repo.asOf(NOW);
    repo.add(row(WIDGETS, "a1", Severity.HIGH));
    repo.add(row(WIDGETS, "a2", Severity.HIGH));
    repo.add(row(GADGETS, "a3", Severity.LOW));
    repo.add(row(THING, "b1", Severity.CRITICAL));
    stages.put(WIDGETS, RolloutStage.STAGE_3);
  }

  @Test
  void theOverviewListsTheSquadsRepositoriesWithStageCoverageAndOpenCounts() {
    coverage.replace(
        new CoverageReport(
            NOW,
            true,
            List.of(
                new RepositoryCoverage(
                    WIDGETS, CoverageState.REPORTING, List.of(), NOW, "1", true, true)),
            List.of()));

    SquadOverview overview = service.overview(ServeFixtures.squadA(), "team-a");

    assertThat(overview.repositories())
        .extracting(SquadOverview.RepositoryLine::repository)
        .containsExactlyInAnyOrder(WIDGETS, GADGETS);
    SquadOverview.RepositoryLine widgets =
        overview.repositories().stream()
            .filter(l -> l.repository().equals(WIDGETS))
            .findFirst()
            .orElseThrow();
    assertThat(widgets.stage()).isEqualTo("STAGE_3");
    assertThat(widgets.coverage()).isEqualTo("REPORTING");
    assertThat(widgets.openBySeverity()).containsEntry("HIGH", 2);
    SquadOverview.RepositoryLine gadgets =
        overview.repositories().stream()
            .filter(l -> l.repository().equals(GADGETS))
            .findFirst()
            .orElseThrow();
    assertThat(gadgets.stage()).isEqualTo("NOT_IN_ROLLOUT");
    assertThat(gadgets.coverage()).isNull();
    assertThat(overview.asOf()).isEqualTo(NOW);
    assertThat(ServeFixtures.json(overview)).doesNotContain(THING);
  }

  @Test
  void theSquadsFindingsCarryTheRunWhoseEvidenceSupportsThem() {
    QueryResult result = service.findings(ServeFixtures.squadA(), "team-a", QueryParams.all());

    assertThat(result.items())
        .extracting(FindingRow::fingerprint)
        .containsExactly("a3", "a1", "a2");
    assertThat(result.items())
        .extracting(FindingRow::runId)
        .containsExactly("run-a3", "run-a1", "run-a2");
  }

  @Test
  void aSquadCanNarrowToOneOfItsRepositories() {
    QueryResult result =
        service.findings(
            ServeFixtures.squadA(),
            "team-a",
            new QueryParams(GADGETS, null, null, null, null, null, null, null));

    assertThat(result.items()).extracting(FindingRow::fingerprint).containsExactly("a3");
  }

  @Test
  void anotherSquadsViewIsRefusedEverywhere() {
    var b = ServeFixtures.squadB();

    assertThatThrownBy(() -> service.overview(b, "team-a"))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.findings(b, "team-a", QueryParams.all()))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.detail(b, "team-a", WIDGETS, "r", "f"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void aRepositoryOutsideTheSquadIsRefusedEvenWithinOwnSquadsUrl() {
    var a = ServeFixtures.squadA();

    assertThatThrownBy(
            () ->
                service.findings(
                    a, "team-a", new QueryParams(THING, null, null, null, null, null, null, null)))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(() -> service.detail(a, "team-a", THING, "r", "f"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void aSquadThatDoesNotExistIsRefusedLikeOneThatIsNotTheCallers() {
    assertThatThrownBy(() -> service.overview(ServeFixtures.squadA(), "team-nobody"))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void callersWithoutTheSquadRoleAreRefused() {
    for (var caller :
        List.of(ServeFixtures.leader(), ServeFixtures.investigatorA(), ServeFixtures.nobody())) {
      assertThatThrownBy(() -> service.overview(caller, "team-a"))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void aFindingIsShownWithItsExplanationAndAMissingOneIsNotFound() {
    runs.save(FindingFixture.run("r1", WIDGETS));
    store.saveRun("r1", WIDGETS, List.of(FindingFixture.finding()));

    var detail =
        service.detail(
            ServeFixtures.squadA(), "team-a", WIDGETS, "r1", FindingFixture.finding().id());

    assertThat(detail.finding().ruleId()).isEqualTo("codemaat.hotspot");
    assertThat(detail.explanation()).isNotNull();
    assertThatThrownBy(
            () -> service.detail(ServeFixtures.squadA(), "team-a", WIDGETS, "r1", "nope"))
        .isInstanceOf(NotFoundException.class);
  }

  @Test
  void aSquadWithNoRepositoriesGetsAnEmptyViewNotAnError() {
    var lone =
        new com.debthunter.controlplane.serve.access.Caller(
            "lone",
            java.util.Set.of(com.debthunter.controlplane.serve.access.Roles.SQUAD),
            java.util.Set.of("team-z"));

    assertThat(service.overview(lone, "team-z").repositories()).isEmpty();
    assertThat(service.findings(lone, "team-z", QueryParams.all()).items()).isEmpty();
  }
}
