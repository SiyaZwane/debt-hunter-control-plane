package com.debthunter.controlplane.governance;

import static com.debthunter.controlplane.governance.GovernanceFixture.run;
import static com.debthunter.controlplane.governance.policy.PolicyYaml.policy;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.governance.simulation.BlockingFinding;
import com.debthunter.controlplane.governance.simulation.Delta;
import com.debthunter.controlplane.governance.simulation.ImpactReport;
import com.debthunter.controlplane.governance.simulation.RepositoryImpact;
import com.debthunter.controlplane.governance.simulation.SimulationOutcome;
import com.debthunter.controlplane.governance.simulation.SimulationRequest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-40: the simulator tells the author which repositories a candidate policy would newly fail,
 * and which findings do it — judged over real runs that went through the real pipeline.
 */
@Tag("integration")
class CpAc40_SimulatorIdentifiesNewFailuresTest {

  private static ImpactReport simulate(GovernanceFixture f, String candidate) {
    SimulationOutcome outcome = f.simulator().simulate(candidate, SimulationRequest.defaults());
    assertThat(outcome).isInstanceOf(SimulationOutcome.Completed.class);
    return ((SimulationOutcome.Completed) outcome).report();
  }

  private static RepositoryImpact.Simulated of(ImpactReport report, String repository) {
    return report.repositories().stream()
        .filter(r -> r.repository().equals(repository))
        .map(RepositoryImpact.Simulated.class::cast)
        .findFirst()
        .orElseThrow();
  }

  @Test
  void cpAc40_aTighterPolicyNewlyFailsTheRepositoriesItShouldAndNamesTheFindings() {
    GovernanceFixture f =
        new GovernanceFixture()
            .currentPolicy(policy("v1").main("no-critical", "CRITICAL", 0).text())
            .onboard("acme/highs", "tier-1", "enforce", "v1")
            .onboard("acme/mediums", "tier-1", "enforce", "v1")
            .onboard("acme/criticals", "tier-1", "enforce", "v1");
    f.apply(
        run("acme/highs", 99, "h-run")
            .finding("high-a", "arch.layering", "HIGH", "ARCHITECTURE", "src/a/A.java", null, null)
            .finding("high-b", "arch.layering", "HIGH", "ARCHITECTURE", "src/b/B.java", null, null)
            .finding("med-a", "style", "MEDIUM", "STATIC_ANALYSIS", "src/C.java", null, null));
    f.apply(
        run("acme/mediums", 99, "m-run")
            .finding("med-b", "style", "MEDIUM", "STATIC_ANALYSIS", "src/D.java", null, null));
    f.apply(
        run("acme/criticals", 99, "c-run")
            .finding(
                "crit-a", "sec.injection", "CRITICAL", "STATIC_ANALYSIS", "src/E.java", null, null)
            .finding("high-c", "arch.layering", "HIGH", "ARCHITECTURE", "src/F.java", null, null));

    ImpactReport report = simulate(f, policy("v2").main("no-high", "HIGH", 0).text());

    // Passes today, fails tomorrow — and exactly the two HIGH findings are why.
    RepositoryImpact.Simulated highs = of(report, "acme/highs");
    assertThat(highs.delta()).isEqualTo(Delta.NEWLY_FAILING);
    assertThat(highs.newlyBlocking())
        .extracting(BlockingFinding::fingerprint)
        .containsExactlyInAnyOrder("high-a", "high-b");
    assertThat(highs.newlyBlocking())
        .allSatisfy(b -> assertThat(b.blockedBy()).contains("no-high"));

    // Mediums are below the candidate's line: unaffected.
    assertThat(of(report, "acme/mediums").delta()).isEqualTo(Delta.UNCHANGED_PASSING);
    assertThat(of(report, "acme/mediums").newlyBlocking()).isEmpty();

    // Already failing on its CRITICAL; the candidate adds the HIGH but not the CRITICAL as new.
    RepositoryImpact.Simulated criticals = of(report, "acme/criticals");
    assertThat(criticals.delta()).isEqualTo(Delta.UNCHANGED_FAILING);
    assertThat(criticals.newlyBlocking())
        .extracting(BlockingFinding::fingerprint)
        .containsExactly("high-c");

    assertThat(report.summary().overall().newlyFailing()).isEqualTo(1);
  }

  @Test
  void cpAc40_aLooserPolicyIsReportedAsNewlyPassingNotNewlyFailing() {
    GovernanceFixture f =
        new GovernanceFixture()
            .currentPolicy(policy("v1").main("no-critical", "CRITICAL", 0).text())
            .onboard("acme/a", "tier-1", "enforce", "v1");
    f.apply(
        run("acme/a", 99, "a-run")
            .finding("crit-a", "sec", "CRITICAL", "STATIC_ANALYSIS", "src/A.java", null, null));

    ImpactReport report = simulate(f, policy("v2").main("few-critical", "CRITICAL", 3).text());

    assertThat(of(report, "acme/a").delta()).isEqualTo(Delta.NEWLY_PASSING);
    assertThat(report.summary().overall().newlyPassing()).isEqualTo(1);
    assertThat(report.summary().overall().newlyFailing()).isZero();
  }

  @Test
  void cpAc40_aRepositorysHistoryShowsHowOftenTheCandidateWouldHaveBlocked() {
    GovernanceFixture f =
        new GovernanceFixture()
            .currentPolicy(policy("v1").main("no-critical", "CRITICAL", 0).text())
            .onboard("acme/a", "tier-1", "enforce", "v1");
    f.apply(run("acme/a", 70, "r1").finding("h1", "x", "HIGH", "HOTSPOT", "A.java", 1.0, null));
    f.apply(run("acme/a", 80, "r2").finding("h2", "x", "HIGH", "HOTSPOT", "B.java", 1.0, null));
    f.apply(run("acme/a", 90, "r3"));

    RepositoryImpact.Simulated impact =
        of(simulate(f, policy("v2").main("no-high", "HIGH", 0).text()), "acme/a");

    assertThat(impact.runsEvaluated()).isEqualTo(3);
    assertThat(impact.runsFailingUnderCandidate()).isEqualTo(2);
    assertThat(impact.runsFailingUnderCurrent()).isZero();
    assertThat(impact.candidatePasses()).as("the latest run is clean").isTrue();
  }
}
