package com.debthunter.controlplane.serve.portfolio;

import static com.debthunter.controlplane.serve.ServeFixtures.CLOCK;
import static com.debthunter.controlplane.serve.ServeFixtures.GADGETS;
import static com.debthunter.controlplane.serve.ServeFixtures.NOW;
import static com.debthunter.controlplane.serve.ServeFixtures.THING;
import static com.debthunter.controlplane.serve.ServeFixtures.WIDGETS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.analytics.coverage.CoverageReport;
import com.debthunter.controlplane.analytics.coverage.CoverageState;
import com.debthunter.controlplane.analytics.coverage.InMemoryCoverageRepository;
import com.debthunter.controlplane.analytics.coverage.RepositoryCoverage;
import com.debthunter.controlplane.analytics.readmodel.InMemoryReadModelRepository;
import com.debthunter.controlplane.analytics.readmodel.ProjectionBatch;
import com.debthunter.controlplane.analytics.readmodel.RunSnapshot;
import com.debthunter.controlplane.analytics.readmodel.Staleness;
import com.debthunter.controlplane.serve.ServeFixtures;
import com.debthunter.controlplane.serve.access.AccessDeniedException;
import com.debthunter.controlplane.store.rollout.RolloutStage;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** CP-AC-75 and the rules around it: leaders see teams, never repositories or people. */
class PortfolioDashboardServiceTest {

  private final InMemoryReadModelRepository readModel = new InMemoryReadModelRepository();
  private final InMemoryCoverageRepository coverage = new InMemoryCoverageRepository();
  private final Map<String, RolloutStage> stages = new HashMap<>();
  private PortfolioDashboardService service;

  @BeforeEach
  void setUp() {
    service =
        new PortfolioDashboardService(
            ServeFixtures.registry(),
            readModel,
            coverage,
            () -> stages,
            new Staleness(readModel, CLOCK, Duration.ofHours(1)),
            CLOCK);
    stages.put(WIDGETS, RolloutStage.STAGE_3);
    stages.put(GADGETS, RolloutStage.STAGE_1);
  }

  private void snapshot(String repository, int critical, int high, String policy) {
    readModel.commit(
        new ProjectionBatch(
            Map.of(),
            List.of(),
            List.of(
                new RunSnapshot(
                    repository,
                    "root",
                    "c-" + repository,
                    "run-" + repository,
                    NOW.minusSeconds(60),
                    critical,
                    high,
                    0,
                    0,
                    0,
                    0,
                    0.0,
                    policy)),
            List.of(),
            List.of(),
            List.of(),
            List.of()),
        NOW.minusSeconds(30));
  }

  @Test
  void aLeaderSeesOneRowPerTeamWithSummedFindings() {
    snapshot(WIDGETS, 1, 4, "PASSED");
    snapshot(GADGETS, 0, 2, "FAILED");
    snapshot(THING, 0, 9, "PASSED");

    PortfolioView view = service.portfolio(ServeFixtures.leader());

    assertThat(view.teams())
        .extracting(PortfolioView.TeamRow::team)
        .containsExactly("team-a", "team-b");
    PortfolioView.TeamRow a = view.teams().get(0);
    assertThat(a.repositories()).isEqualTo(2);
    assertThat(a.findingsBySeverity()).containsEntry("CRITICAL", 1).containsEntry("HIGH", 6);
    assertThat(a.repositoriesFailingPolicy()).isEqualTo(1);
    assertThat(view.teams().get(1).findingsBySeverity()).containsEntry("HIGH", 9);
    assertThat(view.teams().get(1).repositoriesFailingPolicy()).isZero();
  }

  @Test
  void theViewNamesNoRepositoryAndHasNoPlaceToPutOne() {
    snapshot(WIDGETS, 1, 4, "PASSED");

    String json = ServeFixtures.json(service.portfolio(ServeFixtures.leader()));

    assertThat(json).doesNotContain("acme").doesNotContain("widgets").doesNotContain("other/thing");
    assertThat(
            java.util.Arrays.stream(PortfolioView.TeamRow.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
        .containsExactly(
            "team",
            "repositories",
            "findingsBySeverity",
            "repositoriesFailingPolicy",
            "coverage",
            "stages");
  }

  @Test
  void coverageAndStagesAreCountedPerTeam() {
    coverage.replace(
        new CoverageReport(
            NOW.minusSeconds(120),
            true,
            List.of(
                new RepositoryCoverage(
                    WIDGETS, CoverageState.REPORTING, List.of(), NOW, "1", true, true),
                new RepositoryCoverage(
                    GADGETS, CoverageState.SILENT, List.of(), NOW, "1", true, true)),
            List.of()));

    PortfolioView view = service.portfolio(ServeFixtures.leader());

    PortfolioView.TeamRow a = view.teams().get(0);
    assertThat(a.coverage()).containsEntry("REPORTING", 1).containsEntry("SILENT", 1);
    assertThat(a.stages()).containsEntry("STAGE_3", 1).containsEntry("STAGE_1", 1);
    assertThat(view.teams().get(1).coverage()).containsEntry("UNKNOWN", 1);
    assertThat(view.teams().get(1).stages()).containsEntry("NOT_IN_ROLLOUT", 1);
    assertThat(view.coverageAsOf()).isEqualTo(NOW.minusSeconds(120));
  }

  @Test
  void freshReadModelsAreNotMarkedStaleAndOldOnesAre() {
    snapshot(WIDGETS, 0, 1, "PASSED");
    assertThat(service.portfolio(ServeFixtures.leader()).stale()).isFalse();
    assertThat(service.portfolio(ServeFixtures.leader()).readModelsAsOf())
        .isEqualTo(NOW.minusSeconds(30));

    PortfolioDashboardService later =
        new PortfolioDashboardService(
            ServeFixtures.registry(),
            readModel,
            coverage,
            () -> stages,
            new Staleness(
                readModel,
                java.time.Clock.fixed(NOW.plus(Duration.ofHours(3)), java.time.ZoneOffset.UTC),
                Duration.ofHours(1)),
            CLOCK);
    assertThat(later.portfolio(ServeFixtures.leader()).stale()).isTrue();
  }

  @Test
  void readModelsThatNeverRanAreStale() {
    PortfolioView view = service.portfolio(ServeFixtures.leader());

    assertThat(view.stale()).isTrue();
    assertThat(view.readModelsAsOf()).isNull();
    assertThat(view.coverageAsOf()).isNull();
  }

  @Test
  void onlyLeadersMaySeeThePortfolio() {
    for (var caller :
        List.of(ServeFixtures.squadA(), ServeFixtures.investigatorA(), ServeFixtures.nobody())) {
      assertThatThrownBy(() -> service.portfolio(caller)).isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void anEmptyEstateIsAnEmptyPortfolio() {
    PortfolioDashboardService empty =
        new PortfolioDashboardService(
            new com.debthunter.controlplane.store.registry.InMemoryRepositoryRegistry(),
            readModel,
            coverage,
            () -> stages,
            new Staleness(readModel, CLOCK, Duration.ofHours(1)),
            CLOCK);

    assertThat(empty.portfolio(ServeFixtures.leader()).teams()).isEmpty();
    assertThat(Instant.now()).isNotNull();
  }
}
