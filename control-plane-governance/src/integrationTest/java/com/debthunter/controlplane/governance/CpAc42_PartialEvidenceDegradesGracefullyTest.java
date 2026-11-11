package com.debthunter.controlplane.governance;

import static com.debthunter.controlplane.governance.GovernanceFixture.run;
import static com.debthunter.controlplane.governance.policy.PolicyYaml.policy;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.governance.simulation.DelegatingEvidence;
import com.debthunter.controlplane.governance.simulation.ImpactReport;
import com.debthunter.controlplane.governance.simulation.NotSimulatedReason;
import com.debthunter.controlplane.governance.simulation.RepositoryImpact;
import com.debthunter.controlplane.governance.simulation.SimulationOutcome;
import com.debthunter.controlplane.governance.simulation.SimulationRequest;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-42: when evidence is missing the simulator does not pretend. It simulates what it can,
 * names what it could not and why, and states what share of the estate that was — and a repository
 * it could not simulate is never counted as passing.
 */
@Tag("integration")
class CpAc42_PartialEvidenceDegradesGracefullyTest {

  private static final String CANDIDATE = policy("v2").main("no-high", "HIGH", 0).text();

  private static GovernanceFixture estateWithGaps() {
    GovernanceFixture f =
        new GovernanceFixture()
            .currentPolicy(policy("v1").main("no-critical", "CRITICAL", 0).text());
    f.onboard("acme/ok-clean", "tier-1", "enforce", "v1")
        .onboard("acme/ok-dirty", "tier-1", "enforce", "v1")
        .onboard("acme/never-ran", "tier-1", "enforce", "v1")
        .onboard("acme/degraded-only", "tier-1", "enforce", "v1")
        .onboard("acme/pr-only", "tier-1", "enforce", "v1")
        .onboard("acme/too-old", "tier-1", "enforce", "v1")
        .onboard("acme/unreadable", "tier-1", "enforce", "v1");
    f.apply(run("acme/ok-clean", 99, "clean-1"));
    f.apply(
        run("acme/ok-dirty", 99, "dirty-1")
            .finding("h1", "x", "HIGH", "HOTSPOT", "A.java", 1.0, null));
    f.apply(run("acme/degraded-only", 99, "deg-1").degraded());
    f.apply(run("acme/pr-only", 99, "pr-1").pullRequest("base", "42"));
    f.apply(run("acme/too-old", 2, "old-1"));
    f.apply(
        run("acme/unreadable", 99, "unread-1")
            .finding("h2", "x", "HIGH", "HOTSPOT", "B.java", 1.0, null));
    return f;
  }

  private static ImpactReport simulate(GovernanceFixture f) {
    return ((SimulationOutcome.Completed)
            f.simulator().simulate(CANDIDATE, SimulationRequest.defaults()))
        .report();
  }

  private static NotSimulatedReason reasonFor(ImpactReport report, String repository) {
    return report.repositories().stream()
        .filter(r -> r.repository().equals(repository))
        .map(RepositoryImpact.NotSimulated.class::cast)
        .findFirst()
        .orElseThrow()
        .reason();
  }

  @Test
  void cpAc42_whatCannotBeSimulatedIsNamedWithItsReasonAndNeverCountedAsPassing() {
    ImpactReport report = simulate(estateWithGaps());

    assertThat(reasonFor(report, "acme/never-ran")).isEqualTo(NotSimulatedReason.NO_RUNS_IN_WINDOW);
    assertThat(reasonFor(report, "acme/too-old")).isEqualTo(NotSimulatedReason.NO_RUNS_IN_WINDOW);
    assertThat(reasonFor(report, "acme/degraded-only"))
        .isEqualTo(NotSimulatedReason.NO_COMPLETE_RUN_IN_WINDOW);
    assertThat(reasonFor(report, "acme/pr-only"))
        .isEqualTo(NotSimulatedReason.NO_COMPLETE_RUN_IN_WINDOW);

    var overall = report.summary().overall();
    assertThat(overall.simulated()).isEqualTo(3);
    assertThat(overall.passingUnderCandidate()).as("only acme/ok-clean passes").isEqualTo(1);
    assertThat(overall.failingUnderCandidate()).isEqualTo(2);
    assertThat(overall.notSimulated()).isEqualTo(4);
  }

  @Test
  void cpAc42_theProportionThatCouldNotBeSimulatedIsStated() {
    ImpactReport report = simulate(estateWithGaps());

    assertThat(report.notSimulatedShare()).isEqualTo(4.0 / 7.0);
    assertThat(report.isComplete()).isFalse();
    assertThat(report.summary().byTier().get(0).notSimulatedShare()).isEqualTo(4.0 / 7.0);
  }

  @Test
  void cpAc42_aRepositoryWhoseEvidenceIsUnreadableIsLeftOutAndTheRestStillSimulated() {
    GovernanceFixture f = estateWithGaps();
    var broken =
        new DelegatingEvidence(f.evidence()) {
          @Override
          public List<PseudonymisedFinding> findingsOf(String runId) {
            if (runId.equals("unread-1")) {
              throw new IllegalStateException("evidence has been purged");
            }
            return super.findingsOf(runId);
          }
        };

    ImpactReport report =
        ((SimulationOutcome.Completed)
                f.simulatorOver(broken).simulate(CANDIDATE, SimulationRequest.defaults()))
            .report();

    assertThat(reasonFor(report, "acme/unreadable"))
        .isEqualTo(NotSimulatedReason.EVIDENCE_UNAVAILABLE);
    assertThat(report.summary().overall().simulated()).isEqualTo(2);
    assertThat(report.notSimulatedShare()).isEqualTo(5.0 / 7.0);
  }

  @Test
  void cpAc42_aFullyEvidencedEstateReportsItselfComplete() {
    GovernanceFixture f =
        new GovernanceFixture()
            .currentPolicy(policy("v1").main("no-critical", "CRITICAL", 0).text());
    f.onboard("acme/a", "tier-1", "enforce", "v1");
    f.apply(run("acme/a", 99, "a-1"));

    ImpactReport report = simulate(f);

    assertThat(report.isComplete()).isTrue();
    assertThat(report.notSimulatedShare()).isZero();
  }
}
