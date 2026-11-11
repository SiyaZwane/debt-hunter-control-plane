package com.debthunter.controlplane.analytics.coverage;

import static com.debthunter.controlplane.analytics.coverage.CoverageFixture.run;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CP-AC-37: a repository still running an old major version of the tool is flagged as drifted. */
@Tag("integration")
class CpAc37_VersionDriftFlagTest {

  private static final String REPO = "acme/widgets";

  @Test
  void cpAc37_aRepositoryOnAnOldMajorIsFlaggedAndUpgradingClearsIt() {
    CoverageFixture f = PostgresCoverage.over(PostgresCoverage.database(), 1);
    f.onboard(REPO).apply(run(REPO, 98, "r1").toolVersion("0.9.3")).today(100);

    var drifted = f.of(f.tracker.reconcile(), REPO);
    assertThat(drifted.state()).isEqualTo(CoverageState.VERSION_DRIFT);
    assertThat(drifted.lastToolVersion()).isEqualTo("0.9.3");

    f.apply(run(REPO, 99, "r2").toolVersion("1.0.0"));
    assertThat(f.of(f.tracker.reconcile(), REPO).state()).isEqualTo(CoverageState.REPORTING);
  }

  @Test
  void cpAc37_aMinorOrPatchDifferenceWithinTheCurrentMajorIsNotDrift() {
    CoverageFixture f = PostgresCoverage.over(PostgresCoverage.database(), 1);
    f.onboard(REPO).apply(run(REPO, 99, "r1").toolVersion("1.7.2")).today(100);

    assertThat(f.of(f.tracker.reconcile(), REPO).state()).isEqualTo(CoverageState.REPORTING);
  }

  @Test
  void cpAc37_theToolVersionComesFromTheLatestRunNotAnEarlierOne() {
    CoverageFixture f = PostgresCoverage.over(PostgresCoverage.database(), 1);
    f.onboard(REPO)
        .apply(run(REPO, 90, "old").toolVersion("0.1.0"))
        .apply(run(REPO, 99, "new").toolVersion("1.0.0"))
        .today(100);

    assertThat(f.of(f.tracker.reconcile(), REPO).state()).isEqualTo(CoverageState.REPORTING);
  }
}
