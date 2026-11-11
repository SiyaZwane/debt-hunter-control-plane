package com.debthunter.controlplane.analytics.remediation;

import static com.debthunter.controlplane.analytics.AnalyticsFixture.FOREVER;
import static com.debthunter.controlplane.analytics.Days.REPO;
import static com.debthunter.controlplane.analytics.Days.day;
import static com.debthunter.controlplane.analytics.Days.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.analytics.AnalyticsFixture;
import com.debthunter.controlplane.testkit.RunBundleBuilder;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class RemediationEffectivenessCalculatorTest {

  private final AnalyticsFixture f = AnalyticsFixture.inMemory();

  private static RunBundleBuilder with(int day, String rule, String severity, String... fps) {
    RunBundleBuilder b = run(day);
    for (String fp : fps) {
      b.finding(fp, rule, severity, "STATIC_ANALYSIS", fp + ".java", null, null);
    }
    return b;
  }

  @Test
  void cpAc29_theMedianTimeToResolutionIsTheMiddleOfHowLongFixedFindingsWereOpen() {
    f.applyAndProject(with(0, "r", "HIGH", "a", "b", "c"));
    f.applyAndProject(with(2, "r", "HIGH", "b", "c")); // a open 2 days
    f.applyAndProject(with(6, "r", "HIGH", "c")); // b open 6 days
    f.applyAndProject(with(14, "r", "HIGH")); // c open 14 days

    var report = f.remediation.report(REPO, "default", day(0), FOREVER).value();

    assertThat(report.resolvedCount()).isEqualTo(3);
    assertThat(report.medianTimeToResolution()).isEqualTo(Duration.ofDays(6));
  }

  @Test
  void anEvenNumberOfResolutionsAveragesTheTwoMiddleOnes() {
    f.applyAndProject(with(0, "r", "HIGH", "a", "b"));
    f.applyAndProject(with(2, "r", "HIGH", "b")); // a: 2 days
    f.applyAndProject(with(6, "r", "HIGH")); // b: 6 days

    assertThat(
            f.remediation.report(REPO, "default", day(0), FOREVER).value().medianTimeToResolution())
        .isEqualTo(Duration.ofDays(4));
  }

  @Test
  void theMedianIsReportedPerSeverityToo() {
    f.applyAndProject(
        run(0)
            .finding("crit", "r", "CRITICAL", "STATIC_ANALYSIS", "A", null, null)
            .finding("low", "r", "LOW", "STATIC_ANALYSIS", "B", null, null));
    f.applyAndProject(run(1).finding("low", "r", "LOW", "STATIC_ANALYSIS", "B", null, null));
    f.applyAndProject(run(10));

    var report = f.remediation.report(REPO, "default", day(0), FOREVER).value();

    assertThat(report.medianBySeverity()).containsEntry("CRITICAL", Duration.ofDays(1));
    assertThat(report.medianBySeverity()).containsEntry("LOW", Duration.ofDays(10));
  }

  @Test
  void withNothingResolvedThereIsNoMedianRatherThanAZero() {
    f.applyAndProject(with(0, "r", "HIGH", "a"));

    var report = f.remediation.report(REPO, "default", day(0), FOREVER).value();

    assertThat(report.medianTimeToResolution()).isNull();
    assertThat(report.resolvedCount()).isZero();
  }

  @Test
  void cpAc30_aRegressionIsCountedAgainstTheRuleThatRaisedIt() {
    f.applyAndProject(with(0, "flaky", "HIGH", "a"));
    f.applyAndProject(with(1, "flaky", "HIGH"));
    f.applyAndProject(with(2, "flaky", "HIGH", "a")); // regresses
    f.applyAndProject(
        with(3, "flaky", "HIGH", "a")
            .finding("b", "steady", "HIGH", "STATIC_ANALYSIS", "b.java", null, null));
    f.applyAndProject(with(4, "flaky", "HIGH", "a")); // b resolved and stays so

    var byRule = f.remediation.report(REPO, "default", day(0), FOREVER).value().regressionByRule();

    assertThat(byRule).extracting(r -> r.ruleId()).containsExactly("flaky", "steady");
    assertThat(byRule.get(0).regressed()).isEqualTo(1);
    assertThat(byRule.get(0).rate()).isEqualTo(1.0);
    assertThat(byRule.get(1).regressed()).isZero();
    assertThat(byRule.get(1).rate()).isEqualTo(0.0);
  }

  @Test
  void aRuleThatResolvedNothingHasNoRegressionRate() {
    f.applyAndProject(with(0, "new-rule", "HIGH", "a"));

    assertThat(f.remediation.report(REPO, "default", day(0), FOREVER).value().regressionByRule())
        .singleElement()
        .satisfies(r -> assertThat(r.rate()).isNull());
  }

  @Test
  void policyPressureIsTheShareOfRunsWherePolicyFailedOrWouldHave() {
    f.applyAndProject(run(0).policy("PASSED"));
    f.applyAndProject(run(1).policy("FAILED"));
    f.applyAndProject(run(2).policy("WOULD_FAIL"));
    f.applyAndProject(run(3).policy("PASSED"));

    var pressure = f.remediation.report(REPO, "default", day(0), FOREVER).value().policyPressure();

    assertThat(pressure.runs()).isEqualTo(4);
    assertThat(pressure.failing()).isEqualTo(1);
    assertThat(pressure.wouldFail()).isEqualTo(1);
    assertThat(pressure.failingShare()).isEqualTo(0.5);
  }

  @Test
  void policyPressureIsAbsentWithNoRuns() {
    assertThat(
            f.remediation
                .report("nobody/x", "default", day(0), FOREVER)
                .value()
                .policyPressure()
                .failingShare())
        .isNull();
  }

  @Test
  void theWindowOnlyCountsResolutionsInsideIt() {
    f.applyAndProject(with(0, "r", "HIGH", "a", "b"));
    f.applyAndProject(with(2, "r", "HIGH", "b"));
    f.applyAndProject(with(20, "r", "HIGH"));

    assertThat(f.remediation.report(REPO, "default", day(0), day(10)).value().resolvedCount())
        .isEqualTo(1);
    assertThat(f.remediation.report(REPO, "default", day(10), FOREVER).value().resolvedCount())
        .isEqualTo(1);
  }

  @Test
  void theResultCarriesTheStaleFlag() {
    f.applyAndProject(with(0, "r", "HIGH", "a"));
    f.clock.set(f.clock.instant().plus(Duration.ofHours(3)));

    assertThat(f.remediation.report(REPO, "default", day(0), FOREVER).stale()).isTrue();
  }
}
