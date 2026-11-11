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
 * CP-AC-29: resolving a finding updates the median time to resolution. Real bundles go through the
 * real pipeline into PostgreSQL, and the projector turns the resulting transitions into the figure.
 */
@Tag("integration")
class CpAc29_ResolutionUpdatesMedianTest {

  private static RunBundleBuilder open(int day, String... fps) {
    RunBundleBuilder b = run(day);
    for (String fp : fps) {
      b.finding(fp, "rule", "HIGH", "STATIC_ANALYSIS", fp + ".java", null, null);
    }
    return b;
  }

  private static Duration median(AnalyticsFixture f) {
    return f.remediation.report(REPO, "default", day(0), FOREVER).value().medianTimeToResolution();
  }

  @Test
  void cpAc29_eachResolutionMovesTheMedian() {
    AnalyticsFixture f = PostgresAnalytics.fresh();
    f.applyAndProject(open(0, "a", "b", "c"));
    assertThat(median(f)).as("nothing resolved yet").isNull();

    f.applyAndProject(open(2, "b", "c")); // a was open 2 days
    assertThat(median(f)).isEqualTo(Duration.ofDays(2));

    f.applyAndProject(open(6, "c")); // b was open 6 days
    assertThat(median(f)).isEqualTo(Duration.ofDays(4)); // between 2 and 6

    f.applyAndProject(open(14)); // c was open 14 days
    assertThat(median(f)).isEqualTo(Duration.ofDays(6)); // the middle of 2, 6, 14
  }

  @Test
  void cpAc29_theFigureComesFromTheReadModelNotTheLiveStoresSoItNeedsAProjectionPass() {
    AnalyticsFixture f = PostgresAnalytics.fresh();
    f.pipeline.apply(open(0, "a"));
    f.pipeline.apply(open(3)); // resolved in the live stores, but not yet projected

    assertThat(median(f)).isNull();

    f.projector.project();
    assertThat(median(f)).isEqualTo(Duration.ofDays(3));
  }

  @Test
  void cpAc29_aDegradedRunThatCannotResolveAnythingAddsNothingToTheMedian() {
    AnalyticsFixture f = PostgresAnalytics.fresh();
    f.applyAndProject(open(0, "a"));
    f.applyAndProject(run(5).degraded()); // 'a' is absent, but the run was not complete

    assertThat(median(f)).isNull();
    assertThat(f.remediation.report(REPO, "default", day(0), FOREVER).value().resolvedCount())
        .isZero();
  }
}
