package com.debthunter.controlplane.analytics.drift;

import static com.debthunter.controlplane.analytics.AnalyticsFixture.FOREVER;
import static com.debthunter.controlplane.analytics.Days.REPO;
import static com.debthunter.controlplane.analytics.Days.day;
import static com.debthunter.controlplane.analytics.Days.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.debthunter.controlplane.analytics.AnalyticsFixture;
import com.debthunter.controlplane.testkit.RunBundleBuilder;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class ArchitectureDriftTrackerTest {

  private final AnalyticsFixture f = AnalyticsFixture.inMemory();

  private static RunBundleBuilder withViolations(int day, String... fingerprints) {
    RunBundleBuilder b = run(day);
    for (String fp : fingerprints) {
      b.finding(fp, "arch.layering", "HIGH", "ARCHITECTURE", fp + ".java", null, null);
    }
    return b;
  }

  @Test
  void cpAc33_violationsAccumulatingOverTimeMeanPositiveDriftAndTheDriftHasADuration() {
    f.applyAndProject(withViolations(0, "v1"));
    f.applyAndProject(withViolations(7, "v1", "v2"));
    f.applyAndProject(withViolations(14, "v1", "v2", "v3"));

    var drift = f.drift.drift(REPO, "default", day(0), FOREVER, day(14)).value();

    assertThat(drift.series()).extracting(p -> p.violations()).containsExactly(1, 2, 3);
    assertThat(drift.violationsPerWeek()).isEqualTo(1.0, within(1e-9));
    assertThat(drift.driftingFor()).isEqualTo(Duration.ofDays(14));
  }

  @Test
  void aFallInViolationsEndsTheDriftSoOnlyTheCurrentStreakCounts() {
    f.applyAndProject(withViolations(0, "v1", "v2", "v3"));
    f.applyAndProject(withViolations(7, "v1"));
    f.applyAndProject(withViolations(14, "v1", "v2"));
    f.applyAndProject(withViolations(21, "v1", "v2", "v3"));

    var drift = f.drift.drift(REPO, "default", day(0), FOREVER, day(21)).value();

    assertThat(drift.driftingFor()).isEqualTo(Duration.ofDays(14)); // since the dip on day 7
  }

  @Test
  void aPlateauAfterTheLowPointIsNotDrift() {
    f.applyAndProject(withViolations(0, "v1", "v2", "v3"));
    f.applyAndProject(withViolations(7, "v1"));
    f.applyAndProject(withViolations(14, "v1"));

    var drift = f.drift.drift(REPO, "default", day(0), FOREVER, day(14)).value();

    assertThat(drift.driftingFor()).isEqualTo(Duration.ZERO);
    assertThat(drift.violationsPerWeek()).isNegative();
  }

  @Test
  void cpAc33_openViolationsAreAgedAgainstTheDateAsked() {
    f.applyAndProject(withViolations(0, "old"));
    f.applyAndProject(withViolations(10, "old", "mid"));
    f.applyAndProject(withViolations(18, "old", "mid", "new"));

    var drift = f.drift.drift(REPO, "default", day(0), FOREVER, day(20)).value();

    assertThat(drift.openViolations()).isEqualTo(3);
    assertThat(drift.longestOpenAge()).isEqualTo(Duration.ofDays(20));
    assertThat(drift.medianOpenAge()).isEqualTo(Duration.ofDays(10)); // ages 20, 10, 2
  }

  @Test
  void aResolvedViolationStopsAgeing() {
    f.applyAndProject(withViolations(0, "gone", "stays"));
    f.applyAndProject(withViolations(5, "stays"));

    var drift = f.drift.drift(REPO, "default", day(0), FOREVER, day(30)).value();

    assertThat(drift.openViolations()).isEqualTo(1);
    assertThat(drift.longestOpenAge()).isEqualTo(Duration.ofDays(30));
  }

  @Test
  void onlyArchitectureFindingsCountAsDrift() {
    f.applyAndProject(
        run(0)
            .finding("a", "arch", "HIGH", "ARCHITECTURE", "A.java", null, null)
            .finding("b", "style", "HIGH", "STATIC_ANALYSIS", "B.java", null, null));

    var drift = f.drift.drift(REPO, "default", day(0), FOREVER, day(1)).value();

    assertThat(drift.series().get(0).violations()).isEqualTo(1);
    assertThat(drift.openViolations()).isEqualTo(1);
  }

  @Test
  void withTooLittleHistoryThereIsNoDriftToReport() {
    f.applyAndProject(withViolations(0, "v1"));

    var drift = f.drift.drift(REPO, "default", day(0), FOREVER, day(1)).value();

    assertThat(drift.violationsPerWeek()).isZero();
    assertThat(drift.driftingFor()).isEqualTo(Duration.ZERO);
    assertThat(
            f.drift.drift("nobody/x", "default", day(0), FOREVER, day(1)).value().openViolations())
        .isZero();
  }

  @Test
  void theResultCarriesTheStaleFlag() {
    f.applyAndProject(withViolations(0, "v1"));
    f.clock.set(f.clock.instant().plus(Duration.ofHours(3)));

    assertThat(f.drift.drift(REPO, "default", day(0), FOREVER, day(1)).stale()).isTrue();
  }
}
