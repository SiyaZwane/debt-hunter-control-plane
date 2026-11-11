package com.debthunter.controlplane.analytics.coverage;

import static com.debthunter.controlplane.analytics.coverage.CoverageFixture.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-38: coverage is exhaustive. Every repository in the estate or the registry gets exactly one
 * state, and none falls through the cracks — including the ones that are in neither place's good
 * books.
 */
@Tag("integration")
class CpAc38_EstateCoverageExhaustiveTest {

  /** An estate and registry in which every one of the five states occurs. */
  private static CoverageFixture everyState(DataSource dataSource) {
    CoverageFixture f = PostgresCoverage.over(dataSource, 1);
    f.onboard("acme/reporting")
        .onboard("acme/silent")
        .onboard("acme/degraded")
        .onboard("acme/drifted")
        .onboard("acme/gone");
    f.inEstate(
        "acme/reporting", "acme/silent", "acme/degraded", "acme/drifted", "acme/never-onboarded");
    f.apply(run("acme/reporting", 99, "rep").toolVersion("1.0.0"));
    f.apply(run("acme/silent", 40, "sil").toolVersion("1.0.0"));
    f.apply(run("acme/degraded", 96, "d1").toolVersion("1.0.0").degraded());
    f.apply(run("acme/degraded", 97, "d2").toolVersion("1.0.0").degraded());
    f.apply(run("acme/degraded", 98, "d3").toolVersion("1.0.0").degraded());
    f.apply(run("acme/drifted", 99, "dr").toolVersion("0.9.0"));
    f.apply(run("acme/gone", 99, "go").toolVersion("1.0.0"));
    return f.today(100);
  }

  @Test
  void cpAc38_everyRepositoryAppearsOnceAndEveryStateIsRepresented() {
    CoverageFixture f = everyState(PostgresCoverage.database());

    CoverageReport report = f.tracker.reconcile();

    List<String> names =
        report.repositories().stream().map(RepositoryCoverage::repository).toList();
    assertThat(names)
        .containsExactlyInAnyOrder(
            "acme/reporting",
            "acme/silent",
            "acme/degraded",
            "acme/drifted",
            "acme/gone",
            "acme/never-onboarded");
    assertThat(names).doesNotHaveDuplicates();
    assertThat(report.repositories().stream().map(RepositoryCoverage::state).distinct().toList())
        .containsExactlyInAnyOrder(CoverageState.values());
    assertThat(f.of(report, "acme/reporting").state()).isEqualTo(CoverageState.REPORTING);
    assertThat(f.of(report, "acme/silent").state()).isEqualTo(CoverageState.SILENT);
    assertThat(f.of(report, "acme/degraded").state()).isEqualTo(CoverageState.DEGRADED_REPORTING);
    assertThat(f.of(report, "acme/drifted").state()).isEqualTo(CoverageState.VERSION_DRIFT);
    assertThat(f.of(report, "acme/never-onboarded").state())
        .isEqualTo(CoverageState.NEVER_ONBOARDED);
  }

  @Test
  void cpAc38_theCountsAddUpToEveryRepositoryKnown() {
    CoverageReport report = everyState(PostgresCoverage.database()).tracker.reconcile();

    int total =
        Arrays.stream(CoverageState.values()).mapToInt(s -> report.countsByState().get(s)).sum();

    assertThat(total).isEqualTo(report.repositories().size()).isEqualTo(6);
    // acme/reporting, and acme/gone (reporting normally, though missing from the estate listing).
    assertThat(report.healthy())
        .extracting(RepositoryCoverage::repository)
        .containsExactlyInAnyOrder("acme/reporting", "acme/gone");
    assertThat(report.healthyShare()).isEqualTo(2.0 / 6.0);
    assertThat(report.warnings())
        .anyMatch(w -> w.contains("not found in the estate") && w.contains("acme/gone"));
  }

  @Test
  void cpAc38_theStoredReportIsIdenticalToTheOneComputedAndSurvivesARestart() {
    DataSource dataSource = PostgresCoverage.database();
    CoverageFixture f = everyState(dataSource);
    CoverageReport computed = f.tracker.reconcile();

    CoverageReport afterRestart = new JdbcCoverageRepository(dataSource).latest().orElseThrow();

    assertThat(afterRestart).isEqualTo(computed);
  }

  @Test
  void cpAc38_aFailedEstateListingStillCoversEveryOnboardedRepositoryAndSaysWhatItCouldNotSee() {
    CoverageFixture f = everyState(PostgresCoverage.database()).estateDown();

    CoverageReport report = f.tracker.reconcile();

    assertThat(report.repositories()).hasSize(5);
    assertThat(report.estateAvailable()).isFalse();
    assertThat(report.inState(CoverageState.NEVER_ONBOARDED)).isEmpty();
    assertThat(report.warnings()).anyMatch(w -> w.contains("never onboarded cannot be found"));
  }
}
