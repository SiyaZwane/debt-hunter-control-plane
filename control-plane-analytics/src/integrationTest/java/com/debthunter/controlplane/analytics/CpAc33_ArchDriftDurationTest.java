package com.debthunter.controlplane.analytics;

import static com.debthunter.controlplane.analytics.AnalyticsFixture.FOREVER;
import static com.debthunter.controlplane.analytics.Days.REPO;
import static com.debthunter.controlplane.analytics.Days.day;
import static com.debthunter.controlplane.analytics.Days.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.testkit.RunBundleBuilder;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-33: architecture drift has a rate and a duration — how fast violations are accumulating,
 * how long they have been accumulating, and how long the standing ones have been open.
 */
@Tag("integration")
class CpAc33_ArchDriftDurationTest {

  private static RunBundleBuilder violations(int day, String... fps) {
    RunBundleBuilder b = run(day);
    for (String fp : fps) {
      b.finding(fp, "arch.layering", "HIGH", "ARCHITECTURE", fp + ".java", null, null);
    }
    return b;
  }

  @Test
  void cpAc33_theDriftHasARateADurationAndAgeingViolations() {
    AnalyticsFixture f = PostgresAnalytics.fresh();
    f.applyAndProject(violations(0, "v1"));
    f.applyAndProject(violations(7, "v1", "v2"));
    f.applyAndProject(violations(14, "v1", "v2", "v3"));

    var drift = f.drift.drift(REPO, "default", day(0), FOREVER, day(21)).value();

    assertThat(drift.violationsPerWeek()).isEqualTo(1.0);
    assertThat(drift.driftingFor()).isEqualTo(Duration.ofDays(14));
    assertThat(drift.openViolations()).isEqualTo(3);
    assertThat(drift.longestOpenAge()).isEqualTo(Duration.ofDays(21));
    assertThat(drift.medianOpenAge()).isEqualTo(Duration.ofDays(14)); // ages 21, 14, 7
  }

  @Test
  void cpAc33_payingDownViolationsEndsTheDrift() {
    AnalyticsFixture f = PostgresAnalytics.fresh();
    f.applyAndProject(violations(0, "v1", "v2", "v3"));
    f.applyAndProject(violations(7, "v1"));

    var drift = f.drift.drift(REPO, "default", day(0), FOREVER, day(7)).value();

    assertThat(drift.driftingFor()).isEqualTo(Duration.ZERO);
    assertThat(drift.violationsPerWeek()).isEqualTo(-2.0);
    assertThat(drift.openViolations()).isEqualTo(1);
  }
}
