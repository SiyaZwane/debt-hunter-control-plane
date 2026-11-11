package com.debthunter.controlplane.governance.simulation;

import com.debthunter.controlplane.governance.policy.PolicyBundleValidator;
import com.debthunter.controlplane.store.findings.InMemoryPseudonymisedFindingStore;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.registry.InMemoryRepositoryRegistry;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.WorkItemRoute;
import com.debthunter.controlplane.store.runs.InMemoryRunRepository;
import com.debthunter.controlplane.store.runs.RunRecord;
import com.debthunter.controlplane.store.runs.RunStatus;
import com.debthunter.controlplane.store.suppressions.InMemorySuppressionLookup;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import com.debthunter.policy.PolicyBundle;
import com.debthunter.policy.PolicyBundleParser;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** An estate in memory, with real in-memory stores, and a simulator over it. */
final class SimFixture {

  static final Instant NOW = Instant.parse("2026-06-01T00:00:00Z");

  final InMemoryRepositoryRegistry registry = new InMemoryRepositoryRegistry();
  final InMemoryRunRepository runs = new InMemoryRunRepository();
  final InMemoryPseudonymisedFindingStore findings = new InMemoryPseudonymisedFindingStore();
  final InMemorySuppressionLookup suppressions = new InMemorySuppressionLookup();
  final InMemoryPolicyBundleSource bundles = new InMemoryPolicyBundleSource();
  final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private int sequence;

  SimFixture onboard(String repository, String tier, String stage, String policyVersion) {
    registry.save(
        new OnboardedRepository(
            repository, "team", tier, policyVersion, stage, new WorkItemRoute("ado", "board")));
    return this;
  }

  /** The bundle already in force under {@code version}: tolerates nothing at CRITICAL. */
  SimFixture currentPolicy(String version, String yaml) {
    bundles.with(new PolicyBundleParser().parse(yaml));
    return this;
  }

  SimFixture currentPolicy(PolicyBundle bundle) {
    bundles.with(bundle);
    return this;
  }

  static Instant ago(int days) {
    return NOW.minus(Duration.ofDays(days));
  }

  /** A complete, applied run {@code daysAgo} days before now, with these findings. */
  SimFixture run(String repository, String runId, int daysAgo, PseudonymisedFinding... fs) {
    return run(repository, runId, daysAgo, "FULL", RunStatus.ACTIVE, false, false, fs);
  }

  SimFixture run(
      String repository,
      String runId,
      int daysAgo,
      String historyDepth,
      RunStatus status,
      boolean degraded,
      boolean scopeLimited,
      PseudonymisedFinding... fs) {
    runs.save(
        new RunRecord(
            runId,
            repository,
            "default",
            "c-" + runId,
            "0.1.0",
            "key-" + (sequence++),
            degraded,
            scopeLimited,
            List.of(),
            ago(daysAgo),
            status,
            null,
            null,
            ago(daysAgo),
            historyDepth));
    findings.saveRun(runId, repository, List.of(fs));
    return this;
  }

  PolicyImpactSimulator simulator() {
    return simulatorOver(new StoreBackedRetainedEvidence(registry, runs, findings, suppressions));
  }

  PolicyImpactSimulator simulatorOver(RetainedEvidence evidence) {
    return new PolicyImpactSimulator(evidence, bundles, new PolicyBundleValidator(), clock);
  }

  static PseudonymisedFinding finding(String id, Severity severity) {
    return finding(id, severity, Category.HOTSPOT, "src/" + id + ".java", true);
  }

  static PseudonymisedFinding finding(
      String id, Severity severity, Category category, String path, boolean isNew) {
    return new PseudonymisedFinding(
        id,
        "rule." + id,
        category,
        severity,
        1.0,
        path,
        1,
        "message",
        Map.of(),
        1.0,
        isNew,
        "fp-" + id,
        List.of());
  }

  SimulationOutcome.Completed simulate(String yaml) {
    return completed(simulator().simulate(yaml, SimulationRequest.defaults()));
  }

  static SimulationOutcome.Completed completed(SimulationOutcome outcome) {
    if (outcome instanceof SimulationOutcome.Completed c) {
      return c;
    }
    throw new AssertionError("expected the simulation to run but it was rejected: " + outcome);
  }

  static RepositoryImpact.Simulated simulated(ImpactReport report, String repository) {
    RepositoryImpact impact = of(report, repository);
    if (impact instanceof RepositoryImpact.Simulated s) {
      return s;
    }
    throw new AssertionError(repository + " was not simulated: " + impact);
  }

  static RepositoryImpact.NotSimulated notSimulated(ImpactReport report, String repository) {
    RepositoryImpact impact = of(report, repository);
    if (impact instanceof RepositoryImpact.NotSimulated n) {
      return n;
    }
    throw new AssertionError(repository + " was simulated: " + impact);
  }

  private static RepositoryImpact of(ImpactReport report, String repository) {
    return report.repositories().stream()
        .filter(r -> r.repository().equals(repository))
        .findFirst()
        .orElseThrow(() -> new AssertionError(repository + " is not in the report"));
  }
}
