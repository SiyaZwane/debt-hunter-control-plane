package com.debthunter.controlplane.analytics;

import static com.debthunter.controlplane.analytics.AnalyticsFixture.FOREVER;
import static com.debthunter.controlplane.analytics.Days.REPO;
import static com.debthunter.controlplane.analytics.Days.day;
import static com.debthunter.controlplane.analytics.Days.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.analytics.trend.TrendEngine.Direction;
import com.debthunter.controlplane.testkit.RunBundleBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CP-AC-31: the hotspot trend query shows which files are changing more or less often. */
@Tag("integration")
class CpAc31_HotspotTrendQueryTest {

  private static RunBundleBuilder day(int n, double hot, double cooling) {
    return run(n)
        .finding("hot", "codemaat.hotspot", "HIGH", "HOTSPOT", "Hot.java", hot, null)
        .finding("cool", "codemaat.hotspot", "HIGH", "HOTSPOT", "Cooling.java", cooling, null);
  }

  @Test
  void cpAc31_theQueryNamesTheRisingAndTheFallingHotspot() {
    AnalyticsFixture f = PostgresAnalytics.fresh();
    f.applyAndProject(day(1, 4.0, 18.0));
    f.applyAndProject(day(8, 9.0, 11.0));
    f.applyAndProject(day(15, 16.0, 6.0));

    var movement = f.trends.hotspotMovement(REPO, "default", Days.day(0), FOREVER, 10).value();

    assertThat(movement).extracting(m -> m.path()).containsExactly("Hot.java", "Cooling.java");
    assertThat(movement.get(0).direction()).isEqualTo(Direction.RISING);
    assertThat(movement.get(0).delta()).isEqualTo(12.0);
    assertThat(movement.get(1).direction()).isEqualTo(Direction.FALLING);
    assertThat(movement.get(1).delta()).isEqualTo(-12.0);
  }

  @Test
  void cpAc31_theQueryCoversOnlyTheWindowAsked() {
    AnalyticsFixture f = PostgresAnalytics.fresh();
    f.applyAndProject(day(1, 4.0, 18.0));
    f.applyAndProject(day(8, 9.0, 11.0));
    f.applyAndProject(day(15, 16.0, 6.0));

    var firstWeek = f.trends.hotspotMovement(REPO, "default", Days.day(0), Days.day(9), 10).value();

    assertThat(firstWeek.get(0).path()).isEqualTo("Hot.java");
    assertThat(firstWeek.get(0).last()).isEqualTo(9.0); // day 15 is outside the window
    assertThat(firstWeek.get(0).observations()).isEqualTo(2);
  }

  @Test
  void cpAc31_aFileThatStopsBeingAHotspotStopsBeingTrackedFromThatCommit() {
    AnalyticsFixture f = PostgresAnalytics.fresh();
    f.applyAndProject(day(1, 4.0, 18.0));
    f.applyAndProject(
        run(8).finding("hot", "codemaat.hotspot", "HIGH", "HOTSPOT", "Hot.java", 5.0, null));

    var movement = f.trends.hotspotMovement(REPO, "default", Days.day(0), FOREVER, 10).value();

    assertThat(
            movement.stream()
                .filter(m -> m.path().equals("Cooling.java"))
                .findFirst()
                .get()
                .observations())
        .isEqualTo(1);
  }
}
