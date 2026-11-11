package com.debthunter.controlplane.analytics.coverage;

import static com.debthunter.controlplane.analytics.coverage.CoverageFixture.run;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CP-AC-36: a repository whose runs are consistently degraded or shallow is reporting badly. */
@Tag("integration")
class CpAc36_DegradedReportingTest {

  private static final String REPO = "acme/widgets";

  @Test
  void cpAc36_consistentlyDegradedRunsAreFlaggedAndOneGoodRunClearsIt() {
    CoverageFixture f = PostgresCoverage.fresh();
    f.onboard(REPO)
        .apply(run(REPO, 95, "r1").degraded())
        .apply(run(REPO, 96, "r2").degraded())
        .apply(run(REPO, 97, "r3").degraded())
        .today(100);
    assertThat(f.of(f.tracker.reconcile(), REPO).state())
        .isEqualTo(CoverageState.DEGRADED_REPORTING);

    f.apply(run(REPO, 99, "r4"));

    assertThat(f.of(f.tracker.reconcile(), REPO).state()).isEqualTo(CoverageState.REPORTING);
  }

  @Test
  void cpAc36_shallowCloneHistoryIsTheMostCommonCauseAndIsCaught() {
    CoverageFixture f = PostgresCoverage.fresh();
    f.onboard(REPO)
        .apply(run(REPO, 95, "r1").historyDepth("SHALLOW"))
        .apply(run(REPO, 96, "r2").historyDepth("SHALLOW"))
        .apply(run(REPO, 97, "r3").historyDepth("SHALLOW"))
        .today(100);

    var coverage = f.of(f.tracker.reconcile(), REPO);

    assertThat(coverage.state()).isEqualTo(CoverageState.DEGRADED_REPORTING);
    assertThat(coverage.reasons()).singleElement().asString().contains("shallow history");
  }

  @Test
  void cpAc36_theLatestRunsAreWhatCountSoAnOldBadPatchDoesNotHauntARecoveredRepository() {
    CoverageFixture f = PostgresCoverage.fresh();
    f.onboard(REPO)
        .apply(run(REPO, 90, "old1").degraded())
        .apply(run(REPO, 91, "old2").degraded())
        .apply(run(REPO, 92, "old3").degraded())
        .apply(run(REPO, 97, "r1"))
        .apply(run(REPO, 98, "r2"))
        .apply(run(REPO, 99, "r3"))
        .today(100);

    assertThat(f.of(f.tracker.reconcile(), REPO).state()).isEqualTo(CoverageState.REPORTING);
  }
}
