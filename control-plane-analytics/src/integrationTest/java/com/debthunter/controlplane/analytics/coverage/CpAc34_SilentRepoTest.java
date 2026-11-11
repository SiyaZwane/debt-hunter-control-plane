package com.debthunter.controlplane.analytics.coverage;

import static com.debthunter.controlplane.analytics.coverage.CoverageFixture.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-34: a repository that is onboarded but has gone quiet is reported as silent. Real bundles
 * through the real pipeline into PostgreSQL; the registry is the real one too.
 */
@Tag("integration")
class CpAc34_SilentRepoTest {

  private static final String REPO = "acme/widgets";

  @Test
  void cpAc34_aRepositoryThatStopsReportingBecomesSilentOnceTheCadenceWindowPasses() {
    CoverageFixture f = PostgresCoverage.fresh();
    f.onboard(REPO).apply(run(REPO, 10, "r1"));

    f.today(15);
    assertThat(f.of(f.tracker.reconcile(), REPO).state()).isEqualTo(CoverageState.REPORTING);

    f.today(10).clock.set(CoverageFixture.day(10).plus(CoverageFixture.CADENCE).plusSeconds(1));
    var silent = f.of(f.tracker.reconcile(), REPO);
    assertThat(silent.state()).isEqualTo(CoverageState.SILENT);
    assertThat(silent.lastRunReceivedAt()).isEqualTo(CoverageFixture.day(10));
  }

  @Test
  void cpAc34_aRepositoryThatSpeaksAgainIsReportingAgain() {
    CoverageFixture f = PostgresCoverage.fresh();
    f.onboard(REPO).apply(run(REPO, 10, "r1")).today(40);
    assertThat(f.of(f.tracker.reconcile(), REPO).state()).isEqualTo(CoverageState.SILENT);

    f.apply(run(REPO, 39, "r2"));

    assertThat(f.of(f.tracker.reconcile(), REPO).state()).isEqualTo(CoverageState.REPORTING);
  }

  @Test
  void cpAc34_aRepositoryOnboardedButNeverHeardFromIsSilentNotClean() {
    CoverageFixture f = PostgresCoverage.fresh();
    f.onboard(REPO).today(100);

    var coverage = f.of(f.tracker.reconcile(), REPO);

    assertThat(coverage.state()).isEqualTo(CoverageState.SILENT);
    assertThat(f.tracker.reconcile().healthy()).isEmpty();
  }

  @Test
  void cpAc34_theSilenceIsInTheStoredReportASWellNotJustTheReturnedOne() {
    CoverageFixture f = PostgresCoverage.fresh();
    f.onboard(REPO).apply(run(REPO, 10, "r1")).today(60);

    f.tracker.reconcile();

    assertThat(f.tracker.latest().value().get().inState(CoverageState.SILENT))
        .extracting(RepositoryCoverage::repository)
        .containsExactly(REPO);
    assertThat(f.tracker.latest().stale()).isFalse();
    f.clock.set(f.clock.instant().plus(Duration.ofDays(3)));
    assertThat(f.tracker.latest().stale()).as("an old report says so").isTrue();
  }
}
