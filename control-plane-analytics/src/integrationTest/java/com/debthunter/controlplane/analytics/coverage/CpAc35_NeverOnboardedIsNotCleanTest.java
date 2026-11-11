package com.debthunter.controlplane.analytics.coverage;

import static com.debthunter.controlplane.analytics.coverage.CoverageFixture.run;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-35: a repository nobody onboarded has no findings because nobody is looking, which is not
 * the same as being clean. It must never appear among the healthy.
 */
@Tag("integration")
class CpAc35_NeverOnboardedIsNotCleanTest {

  @Test
  void cpAc35_aRepositoryInTheEstateButNotTheRegistryIsNeverOnboardedAndNeverHealthy() {
    CoverageFixture f = PostgresCoverage.fresh();
    f.onboard("acme/watched")
        .inEstate("acme/watched", "acme/forgotten")
        .apply(run("acme/watched", 99, "r1"))
        .today(100);

    CoverageReport report = f.tracker.reconcile();

    assertThat(report.inState(CoverageState.NEVER_ONBOARDED))
        .extracting(RepositoryCoverage::repository)
        .containsExactly("acme/forgotten");
    assertThat(report.healthy())
        .extracting(RepositoryCoverage::repository)
        .doesNotContain("acme/forgotten");
    assertThat(report.countsByState().get(CoverageState.NEVER_ONBOARDED)).isEqualTo(1);
  }

  @Test
  void cpAc35_onboardingItAndHearingFromItIsWhatMakesItCovered() {
    CoverageFixture f = PostgresCoverage.fresh();
    f.inEstate("acme/forgotten").today(100);
    assertThat(f.of(f.tracker.reconcile(), "acme/forgotten").state())
        .isEqualTo(CoverageState.NEVER_ONBOARDED);

    f.onboard("acme/forgotten");
    assertThat(f.of(f.tracker.reconcile(), "acme/forgotten").state())
        .as("onboarded but silent is still not covered")
        .isEqualTo(CoverageState.SILENT);

    f.apply(run("acme/forgotten", 99, "r1"));
    assertThat(f.of(f.tracker.reconcile(), "acme/forgotten").state())
        .isEqualTo(CoverageState.REPORTING);
  }

  @Test
  void cpAc35_aRepositoryThatWasNeverOnboardedHasNoRunsAndNoToolVersion() {
    CoverageFixture f = PostgresCoverage.fresh();
    f.inEstate("acme/forgotten").today(100);

    var coverage = f.of(f.tracker.reconcile(), "acme/forgotten");

    assertThat(coverage.onboarded()).isFalse();
    assertThat(coverage.lastRunReceivedAt()).isNull();
    assertThat(coverage.lastToolVersion()).isNull();
  }
}
