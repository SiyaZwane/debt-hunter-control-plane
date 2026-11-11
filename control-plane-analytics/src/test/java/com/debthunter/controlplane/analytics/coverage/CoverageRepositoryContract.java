package com.debthunter.controlplane.analytics.coverage;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

abstract class CoverageRepositoryContract {

  static final Instant T0 = Instant.parse("2026-03-03T03:03:03Z");

  protected abstract CoverageRepository newRepository();

  CoverageRepository repo;

  @BeforeEach
  void create() {
    repo = newRepository();
  }

  static CoverageReport report(
      Instant at, boolean estateAvailable, List<RepositoryCoverage> repos, String... warnings) {
    return new CoverageReport(at, estateAvailable, repos, List.of(warnings));
  }

  static RepositoryCoverage coverage(String name, CoverageState state, Boolean inEstate) {
    return new RepositoryCoverage(
        name,
        state,
        List.of("a reason", "another \"quoted\" reason, with é and a comma"),
        state == CoverageState.NEVER_ONBOARDED ? null : T0.minusSeconds(3600),
        state == CoverageState.NEVER_ONBOARDED ? null : "0.1.0",
        state != CoverageState.NEVER_ONBOARDED,
        inEstate,
        state == CoverageState.REPORTING
            ? com.debthunter.controlplane.store.rollout.RolloutStage.STAGE_3
            : null);
  }

  @Test
  void beforeAnyReconciliationThereIsNoReport() {
    assertThat(repo.latest()).isEmpty();
  }

  @Test
  void aStoredReportReadsBackWithEveryField() {
    CoverageReport stored =
        report(
            T0,
            true,
            List.of(
                coverage("acme/a", CoverageState.REPORTING, true),
                coverage("acme/b", CoverageState.NEVER_ONBOARDED, true),
                coverage("acme/c", CoverageState.SILENT, false)),
            "a warning",
            "another with \"quotes\"");

    repo.replace(stored);

    assertThat(repo.latest()).contains(stored);
  }

  @Test
  void whetherTheEstateWasConsultedIsKeptIncludingAnUnknownMembership() {
    CoverageReport stored =
        report(
            T0, false, List.of(coverage("acme/a", CoverageState.REPORTING, null)), "estate down");

    repo.replace(stored);

    var back = repo.latest().get();
    assertThat(back.estateAvailable()).isFalse();
    assertThat(back.repositories().get(0).inEstate()).isNull();
  }

  @Test
  void aNewReportReplacesTheOldOneEntirelySoRepositoriesNoLongerPresentDisappear() {
    repo.replace(report(T0, true, List.of(coverage("acme/old", CoverageState.SILENT, true))));
    CoverageReport newer =
        report(
            T0.plusSeconds(86400),
            true,
            List.of(coverage("acme/new", CoverageState.REPORTING, true)));

    repo.replace(newer);

    assertThat(repo.latest().get().repositories())
        .extracting(RepositoryCoverage::repository)
        .containsExactly("acme/new");
    assertThat(repo.latest().get().reconciledAt()).isEqualTo(newer.reconciledAt());
  }

  @Test
  void anEmptyReportIsStillAReport() {
    repo.replace(report(T0, true, List.of()));

    assertThat(repo.latest()).isPresent();
    assertThat(repo.latest().get().repositories()).isEmpty();
  }

  @Test
  void repositoriesComeBackInAStableOrderIgnoringCase() {
    repo.replace(
        report(
            T0,
            true,
            List.of(
                coverage("acme/zeta", CoverageState.REPORTING, true),
                coverage("Acme/alpha", CoverageState.REPORTING, true),
                coverage("acme/Mid", CoverageState.REPORTING, true))));

    assertThat(repo.latest().get().repositories())
        .extracting(RepositoryCoverage::repository)
        .containsExactly("Acme/alpha", "acme/Mid", "acme/zeta");
  }
}
