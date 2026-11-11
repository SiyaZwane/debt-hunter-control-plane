package com.debthunter.controlplane.analytics.trend;

import static com.debthunter.controlplane.analytics.AnalyticsFixture.FOREVER;
import static com.debthunter.controlplane.analytics.Days.REPO;
import static com.debthunter.controlplane.analytics.Days.day;
import static com.debthunter.controlplane.analytics.Days.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.debthunter.controlplane.analytics.AnalyticsFixture;
import com.debthunter.controlplane.analytics.trend.TrendEngine.Direction;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class TrendEngineTest {

  private final AnalyticsFixture f = AnalyticsFixture.inMemory();

  private static final String SEV = "STATIC_ANALYSIS";

  @Test
  void cpAc29_findingCountBySeverityIsASeriesOverTime() {
    f.applyAndProject(
        run(1)
            .finding("a", "r", "CRITICAL", SEV, "A", null, null)
            .finding("b", "r", "LOW", SEV, "B", null, null));
    f.applyAndProject(run(2).finding("b", "r", "LOW", SEV, "B", null, null));
    f.applyAndProject(run(3));

    var series = f.trends.severityOverTime(REPO, "default", day(0), FOREVER).value();

    assertThat(series).extracting(p -> p.total()).containsExactly(2, 1, 0);
    assertThat(series).extracting(p -> p.critical()).containsExactly(1, 0, 0);
    assertThat(series).extracting(p -> p.low()).containsExactly(1, 1, 0);
    assertThat(series).extracting(p -> p.committedAt()).isSorted();
  }

  @Test
  void theWindowIsHalfOpenSoACommitOnTheUpperBoundaryIsExcluded() {
    f.applyAndProject(run(1).observing("a"));
    f.applyAndProject(run(2).observing("a"));

    assertThat(f.trends.severityOverTime(REPO, "default", day(1), day(2)).value()).hasSize(1);
    assertThat(f.trends.severityOverTime(REPO, "default", day(1), day(3)).value()).hasSize(2);
  }

  @Test
  void cpAc29_findingRatesCountCreatedResolvedAndRegressedAndAverageThemPerDay() {
    f.applyAndProject(run(1).observing("a", "b"));
    f.applyAndProject(run(2).observing("b"));
    f.applyAndProject(run(3).observing("a", "b")); // a regresses

    var rates = f.trends.findingRates(REPO, "default", day(0), day(10)).value();

    assertThat(rates.created()).isEqualTo(2);
    assertThat(rates.resolved()).isEqualTo(1);
    assertThat(rates.regressed()).isEqualTo(1);
    assertThat(rates.days()).isEqualTo(10);
    assertThat(rates.createdPerDay()).isEqualTo(0.2, within(1e-9));
    assertThat(rates.resolvedPerDay()).isEqualTo(0.1, within(1e-9));
    assertThat(rates.regressionRate()).isEqualTo(1.0);
    assertThat(rates.daily())
        .extracting(d -> d.day().toString())
        .containsExactly("2026-01-02", "2026-01-03", "2026-01-04");
  }

  @Test
  void theRegressionRateIsAbsentWhenNothingWasResolvedRatherThanADivisionByZero() {
    f.applyAndProject(run(1).observing("a"));

    var rates = f.trends.findingRates(REPO, "default", day(0), day(10)).value();

    assertThat(rates.regressionRate()).isNull();
    assertThat(rates.resolved()).isZero();
  }

  @Test
  void cpAc31_hotspotsThatChangeMoreOftenAreRisingAndListedFirst() {
    f.applyAndProject(
        run(1)
            .finding("a", "hot", "HIGH", "HOTSPOT", "Rising.java", 5.0, null)
            .finding("b", "hot", "HIGH", "HOTSPOT", "Falling.java", 20.0, null)
            .finding("c", "hot", "HIGH", "HOTSPOT", "Steady.java", 8.0, null));
    f.applyAndProject(
        run(2)
            .finding("a", "hot", "HIGH", "HOTSPOT", "Rising.java", 9.0, null)
            .finding("b", "hot", "HIGH", "HOTSPOT", "Falling.java", 12.0, null)
            .finding("c", "hot", "HIGH", "HOTSPOT", "Steady.java", 8.0, null));
    f.applyAndProject(run(3).finding("a", "hot", "HIGH", "HOTSPOT", "Rising.java", 15.0, null));

    var movement = f.trends.hotspotMovement(REPO, "default", day(0), FOREVER, 10).value();

    assertThat(movement)
        .extracting(m -> m.path())
        .containsExactly("Rising.java", "Steady.java", "Falling.java");
    assertThat(movement.get(0).direction()).isEqualTo(Direction.RISING);
    assertThat(movement.get(0).first()).isEqualTo(5.0);
    assertThat(movement.get(0).last()).isEqualTo(15.0);
    assertThat(movement.get(0).delta()).isEqualTo(10.0);
    assertThat(movement.get(0).observations()).isEqualTo(3);
    assertThat(movement.get(1).direction()).isEqualTo(Direction.STEADY);
    assertThat(movement.get(2).direction()).isEqualTo(Direction.FALLING);
    assertThat(movement.get(2).delta()).isEqualTo(-8.0);
  }

  @Test
  void aHotspotSeenOnlyOnceIsSteadyNotRising() {
    f.applyAndProject(run(1).finding("a", "hot", "HIGH", "HOTSPOT", "Once.java", 9.0, null));

    assertThat(f.trends.hotspotMovement(REPO, "default", day(0), FOREVER, 10).value())
        .singleElement()
        .satisfies(m -> assertThat(m.direction()).isEqualTo(Direction.STEADY));
  }

  @Test
  void hotspotMovementHonoursTheLimit() {
    f.applyAndProject(
        run(1)
            .finding("a", "hot", "HIGH", "HOTSPOT", "A.java", 1.0, null)
            .finding("b", "hot", "HIGH", "HOTSPOT", "B.java", 1.0, null)
            .finding("c", "hot", "HIGH", "HOTSPOT", "C.java", 1.0, null));

    assertThat(f.trends.hotspotMovement(REPO, "default", day(0), FOREVER, 2).value()).hasSize(2);
  }

  @Test
  void theComplexityTrajectoryIsTheRunningTotalOfChange() {
    f.applyAndProject(run(1).finding("a", "r", "LOW", SEV, "A", null, 4.0));
    f.applyAndProject(run(2).finding("a", "r", "LOW", SEV, "A", null, -1.5));
    f.applyAndProject(run(3).finding("a", "r", "LOW", SEV, "A", null, 2.0));

    var trajectory = f.trends.complexityTrajectory(REPO, "default", day(0), FOREVER).value();

    assertThat(trajectory).extracting(p -> p.delta()).containsExactly(4.0, -1.5, 2.0);
    assertThat(trajectory).extracting(p -> p.cumulative()).containsExactly(4.0, 2.5, 4.5);
  }

  @Test
  void everyResultCarriesTheStaleFlag() {
    f.applyAndProject(run(1).observing("a"));
    assertThat(f.trends.severityOverTime(REPO, "default", day(0), FOREVER).stale()).isFalse();

    f.clock.set(f.clock.instant().plus(Duration.ofHours(3)));

    assertThat(f.trends.severityOverTime(REPO, "default", day(0), FOREVER).stale()).isTrue();
    assertThat(f.trends.findingRates(REPO, "default", day(0), FOREVER).stale()).isTrue();
    assertThat(f.trends.hotspotMovement(REPO, "default", day(0), FOREVER, 5).stale()).isTrue();
    assertThat(f.trends.complexityTrajectory(REPO, "default", day(0), FOREVER).stale()).isTrue();
  }

  @Test
  void aRepositoryWithNoDataGivesEmptyResultsNotErrors() {
    assertThat(f.trends.severityOverTime("nobody/nothing", "default", day(0), FOREVER).value())
        .isEmpty();
    assertThat(f.trends.findingRates("nobody/nothing", "default", day(0), day(5)).value().created())
        .isZero();
  }
}
