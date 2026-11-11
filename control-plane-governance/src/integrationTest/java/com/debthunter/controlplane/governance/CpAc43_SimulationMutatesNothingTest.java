package com.debthunter.controlplane.governance;

import static com.debthunter.controlplane.governance.GovernanceFixture.run;
import static com.debthunter.controlplane.governance.policy.PolicyYaml.policy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.governance.simulation.SimulationOutcome;
import com.debthunter.controlplane.governance.simulation.SimulationRequest;
import com.debthunter.controlplane.store.runs.JdbcRunRepository;
import com.debthunter.controlplane.store.runs.RunRecord;
import com.debthunter.controlplane.store.runs.RunStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-43: simulating a policy changes nothing. Proved three ways: the whole database is
 * fingerprinted before and after; the simulator is run over connections the database itself will
 * not let write; and the simulator is given a type with no write methods at all.
 */
@Tag("integration")
class CpAc43_SimulationMutatesNothingTest {

  private static GovernanceFixture populated() {
    GovernanceFixture f =
        new GovernanceFixture()
            .currentPolicy(policy("v1").main("no-critical", "CRITICAL", 0).text());
    f.onboard("acme/a", "tier-1", "enforce", "v1")
        .onboard("acme/b", "tier-2", "advisory", "v1")
        .onboard("acme/c", "tier-2", "advisory", "v1");
    f.apply(run("acme/a", 98, "a-1").finding("h1", "x", "HIGH", "HOTSPOT", "A.java", 1.0, null));
    f.apply(run("acme/a", 99, "a-2").finding("h1", "x", "HIGH", "HOTSPOT", "A.java", 1.0, null));
    f.apply(
        run("acme/b", 99, "b-1").finding("c1", "x", "CRITICAL", "HOTSPOT", "B.java", 1.0, null));
    f.apply(run("acme/c", 99, "c-1").degraded());
    f.suppressions.suppress("acme/a", "h1", GovernanceFixture.day(200));
    return f;
  }

  @Test
  void cpAc43_theWholeDatabaseIsIdenticalAfterSimulationsThatChangeEveryVerdict() {
    GovernanceFixture f = populated();
    Map<String, String> before = f.snapshot();
    assertThat(before).as("the snapshot must actually see data").isNotEmpty();

    for (String candidate :
        List.of(
            policy("tight").main("none", "INFO", 0).text(),
            policy("loose").main("lots", "CRITICAL", 99).text(),
            policy("history").main("r", "HIGH", 0).requireHistory("FULL").text(),
            "version: [rejected")) {
      f.simulator().simulate(candidate, SimulationRequest.defaults());
    }
    f.simulator().validate(policy("v").main("r", "HIGH", 0).text(), SimulationRequest.defaults());

    assertThat(f.snapshot()).isEqualTo(before);
  }

  @Test
  void cpAc43_theSnapshotWouldNoticeAChange() {
    GovernanceFixture f = populated();
    Map<String, String> before = f.snapshot();

    f.apply(run("acme/a", 99, "a-3"));

    assertThat(f.snapshot()).as("a real write must change the fingerprint").isNotEqualTo(before);
  }

  @Test
  void cpAc43_overConnectionsThatCannotWriteTheSimulationStillCompletes() {
    GovernanceFixture f = populated();

    SimulationOutcome outcome =
        f.simulator()
            .simulate(policy("v2").main("no-high", "HIGH", 0).text(), SimulationRequest.defaults());

    assertThat(outcome).isInstanceOf(SimulationOutcome.Completed.class);
    assertThat(((SimulationOutcome.Completed) outcome).report().summary().overall().simulated())
        .isEqualTo(2);
  }

  @Test
  void cpAc43_theReadOnlyConnectionsReallyRefuseWrites() {
    // Guards the guard: if this passed, the previous test would prove nothing.
    GovernanceFixture f = populated();
    JdbcRunRepository readOnlyRuns =
        new JdbcRunRepository(GovernanceFixture.readOnlyView(f.dataSource));
    RunRecord run =
        new RunRecord(
            "sneaky",
            "acme/a",
            "default",
            "c",
            "0.1.0",
            "k-sneaky",
            false,
            false,
            List.of(),
            GovernanceFixture.day(50),
            RunStatus.ACTIVE,
            null);

    assertThatThrownBy(() -> readOnlyRuns.save(run)).isInstanceOf(RuntimeException.class);
    assertThat(readOnlyRuns.find("sneaky")).isEmpty();
  }

  @Test
  void cpAc43_runningTheSameSimulationTwiceGivesTheSameAnswer() {
    GovernanceFixture f = populated();
    String candidate = policy("v2").main("no-high", "HIGH", 0).text();

    var first = f.simulator().simulate(candidate, SimulationRequest.defaults());
    var second = f.simulator().simulate(candidate, SimulationRequest.defaults());

    assertThat(second).isEqualTo(first);
  }
}
