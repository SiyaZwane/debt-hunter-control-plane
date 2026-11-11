package com.debthunter.controlplane.governance;

import static com.debthunter.controlplane.governance.policy.PolicyYaml.policy;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.governance.policy.PolicyBundleValidator;
import com.debthunter.controlplane.governance.policy.PolicyIssue;
import com.debthunter.controlplane.governance.simulation.DelegatingEvidence;
import com.debthunter.controlplane.governance.simulation.InMemoryPolicyBundleSource;
import com.debthunter.controlplane.governance.simulation.PolicyImpactSimulator;
import com.debthunter.controlplane.governance.simulation.SimulationOutcome;
import com.debthunter.controlplane.governance.simulation.SimulationRequest;
import com.debthunter.controlplane.governance.simulation.StoreBackedRetainedEvidence;
import com.debthunter.controlplane.store.findings.InMemoryPseudonymisedFindingStore;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.registry.InMemoryRepositoryRegistry;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.WorkItemRoute;
import com.debthunter.controlplane.store.runs.InMemoryRunRepository;
import com.debthunter.controlplane.store.runs.RunRecord;
import com.debthunter.controlplane.store.suppressions.InMemorySuppressionLookup;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-39: a candidate with schema errors is turned back with those errors, before any simulation
 * — and before the system of record is so much as read.
 */
class CpAc39_SchemaErrorsReturnedBeforeSimTest {

  /** Counts every read the simulator makes of the evidence. */
  private static final class CountingEvidence extends DelegatingEvidence {
    final AtomicInteger reads = new AtomicInteger();

    CountingEvidence(com.debthunter.controlplane.governance.simulation.RetainedEvidence inner) {
      super(inner);
    }

    @Override
    public List<OnboardedRepository> estate() {
      reads.incrementAndGet();
      return super.estate();
    }

    @Override
    public List<RunRecord> recentRuns(String repository, int limit) {
      reads.incrementAndGet();
      return super.recentRuns(repository, limit);
    }

    @Override
    public List<PseudonymisedFinding> findingsOf(String runId) {
      reads.incrementAndGet();
      return super.findingsOf(runId);
    }

    @Override
    public boolean suppressedAt(String repository, String fingerprint, Instant at) {
      reads.incrementAndGet();
      return super.suppressedAt(repository, fingerprint, at);
    }
  }

  private final CountingEvidence evidence = counting();

  private static CountingEvidence counting() {
    InMemoryRepositoryRegistry registry = new InMemoryRepositoryRegistry();
    registry.save(
        new OnboardedRepository(
            "acme/a", "team", "tier-1", "v1", "enforce", new WorkItemRoute("ado", "board")));
    return new CountingEvidence(
        new StoreBackedRetainedEvidence(
            registry,
            new InMemoryRunRepository(),
            new InMemoryPseudonymisedFindingStore(),
            new InMemorySuppressionLookup()));
  }

  private PolicyImpactSimulator simulator() {
    return new PolicyImpactSimulator(
        evidence,
        new InMemoryPolicyBundleSource(),
        new PolicyBundleValidator(),
        Clock.fixed(Instant.parse("2026-06-01T00:00:00Z"), ZoneOffset.UTC));
  }

  @Test
  void cpAc39_everyKindOfSchemaErrorComesBackAsARejectionWithTheErrorAndNothingIsRead() {
    String[][] cases = {
      {"malformed yaml", "version: [unclosed"},
      {"no version", "policy:\n  main:\n    rules: []\n"},
      {"bad severity", policy("1").main("r", "SEVERE", 0).text()},
      {"negative threshold", policy("1").main("r", "HIGH", -3).text()},
      {"bad category", policy("1").excludeCategory("NOPE").text()},
      {"empty", ""},
      {"not a mapping", "- a\n- b\n"},
    };
    for (String[] c : cases) {
      SimulationOutcome outcome = simulator().simulate(c[1], SimulationRequest.defaults());

      assertThat(outcome).as(c[0]).isInstanceOf(SimulationOutcome.Rejected.class);
      var errors = ((SimulationOutcome.Rejected) outcome).validation().errors();
      assertThat(errors).as(c[0]).isNotEmpty();
      assertThat(errors.get(0).level()).as(c[0]).isEqualTo(PolicyIssue.Level.ERROR);
      assertThat(errors.get(0).message()).as(c[0]).isNotBlank();
    }
    assertThat(evidence.reads).as("no read of the system of record").hasValue(0);
  }

  @Test
  void cpAc39_theErrorSaysWhereTheProblemIs() {
    SimulationOutcome outcome =
        simulator()
            .simulate(policy("1").main("gate", "SEVERE", 0).text(), SimulationRequest.defaults());

    assertThat(((SimulationOutcome.Rejected) outcome).validation().errors().get(0).message())
        .contains("gate")
        .contains("SEVERE");
  }

  @Test
  void cpAc39_aSemanticErrorIsAlsoReturnedBeforeAnySimulation() {
    SimulationOutcome outcome =
        simulator()
            .simulate(
                policy("1").main("gate", "HIGH", 0).main("gate", "CRITICAL", 1).text(),
                SimulationRequest.defaults());

    assertThat(outcome).isInstanceOf(SimulationOutcome.Rejected.class);
    assertThat(((SimulationOutcome.Rejected) outcome).validation().errors())
        .extracting(PolicyIssue::code)
        .contains("DUPLICATE_RULE_ID");
    assertThat(evidence.reads).hasValue(0);
  }

  @Test
  void cpAc39_aValidCandidateDoesReadTheEvidence() {
    // The control: without this, "zero reads" above could just mean the counter is not wired.
    SimulationOutcome outcome =
        simulator()
            .simulate(policy("1").main("gate", "HIGH", 0).text(), SimulationRequest.defaults());

    assertThat(outcome).isInstanceOf(SimulationOutcome.Completed.class);
    assertThat(evidence.reads.get()).isPositive();
  }
}
