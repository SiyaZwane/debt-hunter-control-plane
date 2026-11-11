package com.debthunter.controlplane.analytics;

import static com.debthunter.controlplane.analytics.AnalyticsFixture.FOREVER;
import static com.debthunter.controlplane.analytics.Days.REPO;
import static com.debthunter.controlplane.analytics.Days.day;
import static com.debthunter.controlplane.analytics.Days.run;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CP-AC-30: a finding that comes back after being resolved raises the regression rate. */
@Tag("integration")
class CpAc30_RegressionIncreasesRateTest {

  private static void withA(AnalyticsFixture f, int day) {
    f.applyAndProject(
        run(day).finding("a", "flaky", "HIGH", "STATIC_ANALYSIS", "A.java", null, null));
  }

  @Test
  void cpAc30_theRegressionRateRisesWhenAResolvedFindingReturns() {
    AnalyticsFixture f = PostgresAnalytics.fresh();
    withA(f, 0);
    f.applyAndProject(run(1)); // resolved
    var before = f.trends.findingRates(REPO, "default", day(0), day(30)).value();
    assertThat(before.regressionRate()).isEqualTo(0.0);

    withA(f, 2); // it comes back

    var after = f.trends.findingRates(REPO, "default", day(0), day(30)).value();
    assertThat(after.regressed()).isEqualTo(1);
    assertThat(after.regressionRate()).isEqualTo(1.0).isGreaterThan(before.regressionRate());
  }

  @Test
  void cpAc30_theRegressionIsAttributedToTheRuleThatKeepsComingBack() {
    AnalyticsFixture f = PostgresAnalytics.fresh();
    withA(f, 0);
    f.applyAndProject(run(1));
    withA(f, 2);

    var byRule = f.remediation.report(REPO, "default", day(0), FOREVER).value().regressionByRule();

    assertThat(byRule)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.ruleId()).isEqualTo("flaky");
              assertThat(r.regressed()).isEqualTo(1);
              assertThat(r.resolved()).isEqualTo(1);
            });
  }

  @Test
  void cpAc30_aRegressionIsNotCountedAsANewFinding() {
    AnalyticsFixture f = PostgresAnalytics.fresh();
    withA(f, 0);
    f.applyAndProject(run(1));
    withA(f, 2);

    assertThat(f.trends.findingRates(REPO, "default", day(0), day(30)).value().created())
        .isEqualTo(1);
  }
}
