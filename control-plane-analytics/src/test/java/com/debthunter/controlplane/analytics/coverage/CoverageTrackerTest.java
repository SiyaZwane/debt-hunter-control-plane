package com.debthunter.controlplane.analytics.coverage;

import static com.debthunter.controlplane.analytics.coverage.CoverageFixture.run;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class CoverageTrackerTest {

  private static final String REPO = "acme/widgets";

  private final CoverageFixture f = new CoverageFixture();

  private CoverageState stateOf(String repository) {
    return f.of(f.tracker.reconcile(), repository).state();
  }

  // ---- reporting and silence (CP-AC-34) ---------------------------------------------------------

  @Test
  void anOnboardedRepositoryThatReportedRecentlyIsReporting() {
    f.onboard(REPO).apply(run(REPO, 98, "r1")).today(100);

    var coverage = f.of(f.tracker.reconcile(), REPO);

    assertThat(coverage.state()).isEqualTo(CoverageState.REPORTING);
    assertThat(coverage.reasons()).isEmpty();
    assertThat(coverage.lastRunReceivedAt()).isEqualTo(CoverageFixture.day(98));
  }

  @Test
  void cpAc34_anOnboardedRepositoryWithNoRunInTheCadenceWindowIsSilent() {
    f.onboard(REPO).apply(run(REPO, 80, "r1")).today(100);

    var coverage = f.of(f.tracker.reconcile(), REPO);

    assertThat(coverage.state()).isEqualTo(CoverageState.SILENT);
    assertThat(coverage.reasons()).singleElement().asString().contains("no run received since");
  }

  @Test
  void cpAc34_theCadenceBoundaryIsInclusiveSoSevenDaysOnIsStillReporting() {
    f.onboard(REPO).apply(run(REPO, 93, "r1"));

    assertThat(f.today(100).tracker.reconcile().repositories().get(0).state())
        .isEqualTo(CoverageState.REPORTING);

    f.clock.set(CoverageFixture.day(100).plusSeconds(1));
    assertThat(stateOf(REPO)).isEqualTo(CoverageState.SILENT);
  }

  @Test
  void cpAc34_aRepositoryThatHasNeverReportedIsSilentNotClean() {
    f.onboard(REPO).today(100);

    var coverage = f.of(f.tracker.reconcile(), REPO);

    assertThat(coverage.state()).isEqualTo(CoverageState.SILENT);
    assertThat(coverage.reasons()).containsExactly("no run has ever been received");
    assertThat(coverage.lastRunReceivedAt()).isNull();
  }

  @Test
  void aPullRequestOnlyPipelineIsStillReporting() {
    f.onboard(REPO).apply(run(REPO, 99, "pr").pullRequest("c0", "5")).today(100);

    assertThat(stateOf(REPO)).isEqualTo(CoverageState.REPORTING);
  }

  // ---- never onboarded (CP-AC-35) ---------------------------------------------------------------

  @Test
  void cpAc35_aRepositoryInTheEstateButNotTheRegistryIsNeverOnboarded() {
    f.onboard(REPO).inEstate(REPO, "acme/forgotten").apply(run(REPO, 99, "r1")).today(100);

    var coverage = f.of(f.tracker.reconcile(), "acme/forgotten");

    assertThat(coverage.state()).isEqualTo(CoverageState.NEVER_ONBOARDED);
    assertThat(coverage.onboarded()).isFalse();
    assertThat(coverage.inEstate()).isTrue();
    assertThat(coverage.lastRunReceivedAt()).isNull();
  }

  @Test
  void cpAc35_aRepositoryNobodyOnboardedIsNeverCountedAsHealthy() {
    f.onboard(REPO).inEstate(REPO, "acme/forgotten").apply(run(REPO, 99, "r1")).today(100);

    CoverageReport report = f.tracker.reconcile();

    assertThat(report.healthy()).extracting(RepositoryCoverage::repository).containsExactly(REPO);
    assertThat(report.uncovered())
        .extracting(RepositoryCoverage::repository)
        .containsExactly("acme/forgotten");
    assertThat(report.healthyShare()).isEqualTo(0.5);
  }

  // ---- degraded (CP-AC-36) ----------------------------------------------------------------------

  @Test
  void cpAc36_consistentlyDegradedRunsAreDegradedReporting() {
    f.onboard(REPO)
        .apply(run(REPO, 95, "r1").degraded())
        .apply(run(REPO, 96, "r2").degraded())
        .apply(run(REPO, 97, "r3").degraded())
        .today(100);

    var coverage = f.of(f.tracker.reconcile(), REPO);

    assertThat(coverage.state()).isEqualTo(CoverageState.DEGRADED_REPORTING);
    assertThat(coverage.reasons())
        .singleElement()
        .asString()
        .contains("degraded or analysed with shallow history");
  }

  @Test
  void cpAc36_oneGoodRunAmongTheLatestMeansItIsNotConsistentlyDegraded() {
    f.onboard(REPO)
        .apply(run(REPO, 95, "r1").degraded())
        .apply(run(REPO, 96, "r2"))
        .apply(run(REPO, 97, "r3").degraded())
        .today(100);

    assertThat(stateOf(REPO)).isEqualTo(CoverageState.REPORTING);
  }

  @Test
  void cpAc36_tooFewRunsToCallItConsistentIsNotDegraded() {
    f.onboard(REPO)
        .apply(run(REPO, 96, "r1").degraded())
        .apply(run(REPO, 97, "r2").degraded())
        .today(100);

    assertThat(stateOf(REPO)).isEqualTo(CoverageState.REPORTING);
  }

  @Test
  void cpAc36_shallowHistoryCountsTheSameAsADegradedRun() {
    f.onboard(REPO)
        .apply(run(REPO, 95, "r1").historyDepth("SHALLOW"))
        .apply(run(REPO, 96, "r2").historyDepth("SHALLOW"))
        .apply(run(REPO, 97, "r3").degraded())
        .today(100);

    assertThat(stateOf(REPO)).isEqualTo(CoverageState.DEGRADED_REPORTING);
  }

  @Test
  void partialHistoryIsNotShallow() {
    f.onboard(REPO)
        .apply(run(REPO, 95, "r1").historyDepth("PARTIAL"))
        .apply(run(REPO, 96, "r2").historyDepth("PARTIAL"))
        .apply(run(REPO, 97, "r3").historyDepth("PARTIAL"))
        .today(100);

    assertThat(stateOf(REPO)).isEqualTo(CoverageState.REPORTING);
  }

  // ---- version drift (CP-AC-37) -----------------------------------------------------------------

  @Test
  void cpAc37_aToolVersionThatIsNotTheCurrentMajorIsVersionDrift() {
    CoverageFixture newer =
        new CoverageFixture(
            new com.debthunter.controlplane.store.registry.InMemoryRepositoryRegistry(),
            com.debthunter.controlplane.analytics.PipelineDriver.inMemory(),
            new InMemoryCoverageRepository(),
            1); // the current major is 1, the data plane in the bundles is 0.1.0
    newer.onboard(REPO).apply(run(REPO, 99, "r1")).today(100);

    var coverage = newer.of(newer.tracker.reconcile(), REPO);

    assertThat(coverage.state()).isEqualTo(CoverageState.VERSION_DRIFT);
    assertThat(coverage.reasons())
        .singleElement()
        .asString()
        .contains("0.1.0")
        .contains("current major 1");
    assertThat(coverage.lastToolVersion()).isEqualTo("0.1.0");
  }

  @Test
  void cpAc37_aToolVersionOfTheCurrentMajorIsNotDrift() {
    f.onboard(REPO).apply(run(REPO, 99, "r1").toolVersion("0.9.4")).today(100);

    assertThat(stateOf(REPO)).isEqualTo(CoverageState.REPORTING);
  }

  @Test
  void cpAc37_aVersionNobodyCanParseCannotBeCalledCurrent() {
    f.onboard(REPO).apply(run(REPO, 99, "r1").toolVersion("dev-snapshot")).today(100);

    var coverage = f.of(f.tracker.reconcile(), REPO);

    assertThat(coverage.state()).isEqualTo(CoverageState.VERSION_DRIFT);
    assertThat(coverage.reasons()).singleElement().asString().contains("not recognisable");
  }

  @Test
  void majorVersionsAreReadFromTheLeadingNumber() {
    assertThat(CoverageTracker.majorOf("0.1.0")).isEqualTo(0);
    assertThat(CoverageTracker.majorOf("12.4.1-rc1")).isEqualTo(12);
    assertThat(CoverageTracker.majorOf("v1.0")).isNull();
    assertThat(CoverageTracker.majorOf("")).isNull();
    assertThat(CoverageTracker.majorOf(null)).isNull();
    assertThat(CoverageTracker.majorOf("99999999999.0")).isNull();
  }

  // ---- precedence
  // --------------------------------------------------------------------------------

  @Test
  void silenceOutranksEverythingElseAndIsTheOnlyReasonGiven() {
    f.onboard(REPO)
        .apply(run(REPO, 70, "r1").degraded().toolVersion("9.0.0"))
        .apply(run(REPO, 71, "r2").degraded().toolVersion("9.0.0"))
        .apply(run(REPO, 72, "r3").degraded().toolVersion("9.0.0"))
        .today(100);

    var coverage = f.of(f.tracker.reconcile(), REPO);

    assertThat(coverage.state()).isEqualTo(CoverageState.SILENT);
    assertThat(coverage.reasons()).hasSize(1);
  }

  @Test
  void degradedOutranksDriftButTheReasonsNameBoth() {
    f.onboard(REPO)
        .apply(run(REPO, 95, "r1").degraded().toolVersion("9.0.0"))
        .apply(run(REPO, 96, "r2").degraded().toolVersion("9.0.0"))
        .apply(run(REPO, 97, "r3").degraded().toolVersion("9.0.0"))
        .today(100);

    var coverage = f.of(f.tracker.reconcile(), REPO);

    assertThat(coverage.state()).isEqualTo(CoverageState.DEGRADED_REPORTING);
    assertThat(coverage.reasons()).hasSize(2);
  }

  // ---- exhaustiveness (CP-AC-38)
  // -----------------------------------------------------------------

  @Test
  void cpAc38_everyRepositoryInTheEstateOrTheRegistryAppearsExactlyOnce() {
    f.onboard("acme/reporting")
        .onboard("acme/silent")
        .onboard("acme/removed-from-estate")
        .inEstate("acme/reporting", "acme/silent", "acme/never-onboarded", "acme/also-never")
        .apply(run("acme/reporting", 99, "r1"))
        .apply(run("acme/silent", 50, "r2"))
        .today(100);

    CoverageReport report = f.tracker.reconcile();

    List<String> names =
        report.repositories().stream().map(RepositoryCoverage::repository).toList();
    assertThat(names)
        .containsExactlyInAnyOrder(
            "acme/reporting",
            "acme/silent",
            "acme/removed-from-estate",
            "acme/never-onboarded",
            "acme/also-never");
    assertThat(names).doesNotHaveDuplicates();
    assertThat(report.repositories()).allSatisfy(r -> assertThat(r.state()).isNotNull());
  }

  @Test
  void cpAc38_everyStateIsPresentInTheCountsEvenWhenNoRepositoryIsInIt() {
    f.onboard(REPO).apply(run(REPO, 99, "r1")).today(100);

    var counts = f.tracker.reconcile().countsByState();

    assertThat(counts.keySet()).containsExactlyInAnyOrder(CoverageState.values());
    assertThat(counts.get(CoverageState.REPORTING)).isEqualTo(1);
    assertThat(counts.get(CoverageState.NEVER_ONBOARDED)).isZero();
  }

  @Test
  void repositoriesAreMatchedIgnoringCaseSoARenamedCaseIsNotReportedAsMissing() {
    f.onboard("Acme/Widgets")
        .inEstate("acme/widgets")
        .apply(run("Acme/Widgets", 99, "r1"))
        .today(100);

    CoverageReport report = f.tracker.reconcile();

    assertThat(report.repositories()).hasSize(1);
    assertThat(report.repositories().get(0).repository()).isEqualTo("Acme/Widgets");
    assertThat(report.repositories().get(0).inEstate()).isTrue();
  }

  @Test
  void aRepositoryListedTwiceByTheEstateIsStillOneRepository() {
    f.inEstate("acme/dup", "ACME/DUP").today(100);

    assertThat(f.tracker.reconcile().repositories()).hasSize(1);
  }

  @Test
  void anOnboardedRepositoryMissingFromTheEstateIsFlaggedButKeepsItsRunBasedState() {
    f.onboard(REPO).inEstate("acme/other").apply(run(REPO, 99, "r1")).today(100);

    CoverageReport report = f.tracker.reconcile();

    var coverage = f.of(report, REPO);
    assertThat(coverage.state()).isEqualTo(CoverageState.REPORTING);
    assertThat(coverage.inEstate()).isFalse();
    assertThat(report.warnings())
        .anyMatch(w -> w.contains("not found in the estate") && w.contains(REPO));
  }

  // ---- when the estate cannot be consulted
  // --------------------------------------------------------

  @Test
  void anEstateThatCannotBeListedIsReportedAsSuchNotAsEmpty() {
    f.onboard(REPO).apply(run(REPO, 99, "r1")).estateDown().today(100);

    CoverageReport report = f.tracker.reconcile();

    assertThat(report.estateAvailable()).isFalse();
    assertThat(report.warnings())
        .anyMatch(w -> w.contains("could not be listed") && w.contains("never onboarded"));
    assertThat(f.of(report, REPO).state()).isEqualTo(CoverageState.REPORTING);
    assertThat(f.of(report, REPO).inEstate()).isNull();
    assertThat(report.inState(CoverageState.NEVER_ONBOARDED)).isEmpty();
  }

  @Test
  void anEmptyEstateIsAcceptedButQueried() {
    f.onboard(REPO).apply(run(REPO, 99, "r1")).today(100);

    CoverageReport report = f.tracker.reconcile();

    assertThat(report.estateAvailable()).isTrue();
    assertThat(report.warnings()).anyMatch(w -> w.contains("listing was empty"));
  }

  @Test
  void withNoEstateConfiguredSilenceIsStillDetected() {
    var noEstate =
        new CoverageTracker(
            f.registry,
            f.pipeline.runs,
            null,
            f.coverage,
            new CoverageSettings(CoverageFixture.CADENCE, 0, 3),
            f.clock,
            new com.debthunter.controlplane.analytics.readmodel.Staleness(
                () -> f.coverage.latest().map(CoverageReport::reconciledAt),
                f.clock,
                Duration.ofHours(36)));
    f.onboard(REPO).today(100);

    CoverageReport report = noEstate.reconcile();

    assertThat(report.estateAvailable()).isFalse();
    assertThat(report.warnings())
        .anyMatch(w -> w.contains("No source-control estate is configured"));
    assertThat(f.of(report, REPO).state()).isEqualTo(CoverageState.SILENT);
  }

  @Test
  void twoOnboardedNamesThatDifferOnlyByCaseAreTreatedAsOneAndSaidSo() {
    f.onboard("acme/Widgets").onboard("acme/widgets").today(100);

    CoverageReport report = f.tracker.reconcile();

    assertThat(report.repositories()).hasSize(1);
    assertThat(report.warnings()).anyMatch(w -> w.contains("differ only by case"));
  }

  // ---- the stored report
  // ---------------------------------------------------------------------------

  @Test
  void theLatestReportIsStoredAndFlaggedStaleWhenOld() {
    f.onboard(REPO).apply(run(REPO, 99, "r1")).today(100);
    assertThat(f.tracker.latest().value()).isEmpty();
    assertThat(f.tracker.latest().stale()).as("never reconciled").isTrue();

    f.tracker.reconcile();
    assertThat(f.tracker.latest().value()).isPresent();
    assertThat(f.tracker.latest().stale()).isFalse();

    f.clock.set(f.clock.instant().plus(Duration.ofHours(40)));
    assertThat(f.tracker.latest().stale()).isTrue();
  }

  @Test
  void theReportStoredIsTheOneJustReturned() {
    f.onboard(REPO).inEstate(REPO, "acme/forgotten").apply(run(REPO, 99, "r1")).today(100);

    CoverageReport report = f.tracker.reconcile();

    assertThat(f.coverage.latest()).contains(report);
    assertThat(
            report.repositories().stream()
                .collect(
                    Collectors.toMap(RepositoryCoverage::repository, RepositoryCoverage::state)))
        .containsEntry("acme/forgotten", CoverageState.NEVER_ONBOARDED);
  }
}
