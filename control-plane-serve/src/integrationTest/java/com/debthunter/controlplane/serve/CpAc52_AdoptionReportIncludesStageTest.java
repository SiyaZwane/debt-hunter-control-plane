package com.debthunter.controlplane.serve;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.debthunter.controlplane.analytics.coverage.CoverageReport;
import com.debthunter.controlplane.analytics.coverage.CoverageSettings;
import com.debthunter.controlplane.analytics.coverage.CoverageTracker;
import com.debthunter.controlplane.analytics.coverage.JdbcCoverageRepository;
import com.debthunter.controlplane.analytics.coverage.RepositoryCoverage;
import com.debthunter.controlplane.analytics.readmodel.Staleness;
import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.store.registry.JdbcRepositoryRegistry;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistryService;
import com.debthunter.controlplane.store.registry.WorkItemRoute;
import com.debthunter.controlplane.store.rollout.JdbcStageRepository;
import com.debthunter.controlplane.store.rollout.RolloutStage;
import com.debthunter.controlplane.store.rollout.StageEvent;
import com.debthunter.controlplane.store.rollout.StageRecord;
import com.debthunter.controlplane.store.runs.JdbcRunRepository;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-52: the adoption and coverage report shows each repository's rollout stage, and it is still
 * there when the report is read back from PostgreSQL rather than recomputed.
 */
@Tag("integration")
class CpAc52_AdoptionReportIncludesStageTest {

  private static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

  private final DataSource dataSource = PostgresTestDatabase.freshSchema();
  private final JdbcStageRepository stages;
  private final RepositoryRegistryService registry;
  private final CoverageTracker tracker;

  CpAc52_AdoptionReportIncludesStageTest() {
    StoreMigrations.migrate(dataSource);
    this.stages = new JdbcStageRepository(dataSource);
    this.registry = new RepositoryRegistryService(new JdbcRepositoryRegistry(dataSource));
    Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    JdbcCoverageRepository coverage = new JdbcCoverageRepository(dataSource);
    this.tracker =
        new CoverageTracker(
            registry,
            new JdbcRunRepository(dataSource),
            () -> List.of("acme/alpha", "acme/beta", "acme/gamma", "acme/unmanaged"),
            coverage,
            new CoverageSettings(Duration.ofDays(7), 0, 3),
            clock,
            new Staleness(
                () -> coverage.latest().map(CoverageReport::reconciledAt),
                clock,
                Duration.ofHours(36)),
            stages);
  }

  private void onboard(String repository) {
    registry.onboard(
        new OnboardedRepository(
            repository, "team", "tier-1", "1.0", "enforce", new WorkItemRoute("jira", "X")));
  }

  private void enrolAt(String repository, RolloutStage stage) {
    stages.apply(
        new StageRecord(repository, stage, NOW.minusSeconds(3600), "team", Map.of(), null, null),
        new StageEvent(
            repository,
            StageEvent.Kind.ENROLLED,
            null,
            stage,
            NOW.minusSeconds(3600),
            "lead",
            Map.of(),
            null));
  }

  @Test
  void cpAc52_theReportShowsEachRepositorysStageAndKeepsItWhenReadBack() {
    onboard("acme/alpha");
    onboard("acme/beta");
    onboard("acme/gamma");
    onboard("acme/unmanaged");
    enrolAt("acme/alpha", RolloutStage.STAGE_1);
    enrolAt("acme/beta", RolloutStage.STAGE_2);
    enrolAt("acme/gamma", RolloutStage.STAGE_3);

    tracker.reconcile();
    CoverageReport stored = tracker.latest().value().orElseThrow();

    assertThat(stored.repositories())
        .extracting(RepositoryCoverage::repository, RepositoryCoverage::rolloutStage)
        .containsExactly(
            tuple("acme/alpha", RolloutStage.STAGE_1),
            tuple("acme/beta", RolloutStage.STAGE_2),
            tuple("acme/gamma", RolloutStage.STAGE_3),
            tuple("acme/unmanaged", null));
  }

  @Test
  void cpAc52_aRepositoryNobodyOnboardedStillShowsItsStageIfItIsInTheRollout() {
    enrolAt("acme/alpha", RolloutStage.STAGE_2);

    CoverageReport report = tracker.reconcile();

    assertThat(report.repositories())
        .filteredOn(r -> r.repository().equals("acme/alpha"))
        .extracting(RepositoryCoverage::onboarded, RepositoryCoverage::rolloutStage)
        .containsExactly(tuple(false, RolloutStage.STAGE_2));
  }

  @Test
  void cpAc52_aStageChangeShowsInTheNextReport() {
    onboard("acme/alpha");
    enrolAt("acme/alpha", RolloutStage.STAGE_1);
    tracker.reconcile();

    stages.apply(
        new StageRecord("acme/alpha", RolloutStage.STAGE_2, NOW, "team", Map.of(), null, null),
        new StageEvent(
            "acme/alpha",
            StageEvent.Kind.ADVANCED,
            RolloutStage.STAGE_1,
            RolloutStage.STAGE_2,
            NOW,
            "lead",
            Map.of("c", "e"),
            null));
    tracker.reconcile();

    assertThat(
            tracker.latest().value().orElseThrow().repositories().stream()
                .filter(r -> r.repository().equals("acme/alpha"))
                .findFirst()
                .orElseThrow()
                .rolloutStage())
        .isEqualTo(RolloutStage.STAGE_2);
  }
}
