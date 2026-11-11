package com.debthunter.controlplane.governance;

import static com.debthunter.controlplane.governance.GovernanceFixture.run;
import static com.debthunter.controlplane.governance.policy.PolicyYaml.policy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.debthunter.controlplane.governance.simulation.EstateSummary;
import com.debthunter.controlplane.governance.simulation.GroupSummary;
import com.debthunter.controlplane.governance.simulation.SimulationOutcome;
import com.debthunter.controlplane.governance.simulation.SimulationRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-41: the estate-level summary is broken down by criticality tier and by rollout stage, so
 * the author can see where a policy would bite and where it would merely nudge.
 */
@Tag("integration")
class CpAc41_EstateSummaryByTierAndStageTest {

  private static void clean(GovernanceFixture f, String repo, int n) {
    f.apply(run(repo, 99, "run-" + n));
  }

  private static void high(GovernanceFixture f, String repo, int n) {
    f.apply(
        run(repo, 99, "run-" + n).finding("h" + n, "x", "HIGH", "HOTSPOT", "A.java", 1.0, null));
  }

  @Test
  void cpAc41_theSummaryIsBrokenDownByTierAndByStage() {
    GovernanceFixture f =
        new GovernanceFixture()
            .currentPolicy(policy("v1").main("no-critical", "CRITICAL", 0).text());
    f.onboard("acme/t1-enforce-a", "tier-1", "enforce", "v1")
        .onboard("acme/t1-enforce-b", "tier-1", "enforce", "v1")
        .onboard("acme/t1-advisory", "tier-1", "advisory", "v1")
        .onboard("acme/t2-enforce", "tier-2", "enforce", "v1")
        .onboard("acme/t2-advisory-a", "tier-2", "advisory", "v1")
        .onboard("acme/t2-advisory-b", "tier-2", "advisory", "v1");
    high(f, "acme/t1-enforce-a", 1);
    clean(f, "acme/t1-enforce-b", 2);
    high(f, "acme/t1-advisory", 3);
    high(f, "acme/t2-enforce", 4);
    high(f, "acme/t2-advisory-a", 5);
    clean(f, "acme/t2-advisory-b", 6);

    EstateSummary summary =
        ((SimulationOutcome.Completed)
                f.simulator()
                    .simulate(
                        policy("v2").main("no-high", "HIGH", 0).text(),
                        SimulationRequest.defaults()))
            .report()
            .summary();

    assertThat(summary.overall().repositories()).isEqualTo(6);
    assertThat(summary.overall().failingUnderCandidate()).isEqualTo(4);
    assertThat(summary.overall().newlyFailing()).isEqualTo(4);

    assertThat(summary.byTier())
        .extracting(
            GroupSummary::tier,
            GroupSummary::repositories,
            GroupSummary::failingUnderCandidate,
            GroupSummary::passingUnderCandidate)
        .containsExactly(tuple("tier-1", 3, 2, 1), tuple("tier-2", 3, 2, 1));

    assertThat(summary.byStage())
        .extracting(
            GroupSummary::stage,
            GroupSummary::repositories,
            GroupSummary::failingUnderCandidate,
            GroupSummary::passingUnderCandidate)
        .containsExactly(tuple("advisory", 3, 2, 1), tuple("enforce", 3, 2, 1));

    assertThat(summary.byTierAndStage())
        .extracting(
            GroupSummary::tier,
            GroupSummary::stage,
            GroupSummary::repositories,
            GroupSummary::newlyFailing)
        .containsExactly(
            tuple("tier-1", "advisory", 1, 1),
            tuple("tier-1", "enforce", 2, 1),
            tuple("tier-2", "advisory", 2, 1),
            tuple("tier-2", "enforce", 1, 1));
  }

  @Test
  void cpAc41_everySliceAddsUpToTheWholeEstate() {
    GovernanceFixture f =
        new GovernanceFixture()
            .currentPolicy(policy("v1").main("no-critical", "CRITICAL", 0).text());
    for (int i = 0; i < 8; i++) {
      f.onboard("acme/r" + i, "tier-" + (i % 3), i % 2 == 0 ? "enforce" : "advisory", "v1");
      if (i % 3 != 0) {
        high(f, "acme/r" + i, i);
      }
    }

    EstateSummary summary =
        ((SimulationOutcome.Completed)
                f.simulator()
                    .simulate(
                        policy("v2").main("no-high", "HIGH", 0).text(),
                        SimulationRequest.defaults()))
            .report()
            .summary();

    for (var grouping :
        java.util.List.of(summary.byTier(), summary.byStage(), summary.byTierAndStage())) {
      assertThat(grouping.stream().mapToInt(GroupSummary::repositories).sum()).isEqualTo(8);
      assertThat(grouping.stream().mapToInt(GroupSummary::failingUnderCandidate).sum())
          .isEqualTo(summary.overall().failingUnderCandidate());
      assertThat(grouping.stream().mapToInt(GroupSummary::newlyFailing).sum())
          .isEqualTo(summary.overall().newlyFailing());
    }
  }
}
