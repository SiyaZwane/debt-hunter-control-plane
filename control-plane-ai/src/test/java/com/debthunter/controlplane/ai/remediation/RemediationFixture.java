package com.debthunter.controlplane.ai.remediation;

import com.debthunter.controlplane.ai.AiFixtures;
import com.debthunter.controlplane.ai.MutableClock;
import com.debthunter.controlplane.ai.remediation.host.FakeSourceHost;
import com.debthunter.controlplane.store.findings.InMemoryPseudonymisedFindingStore;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.queue.InMemoryWorkQueue;
import com.debthunter.controlplane.store.runs.EngineRecord;
import com.debthunter.controlplane.store.runs.InMemoryRunRepository;
import com.debthunter.controlplane.store.runs.RunRecord;
import com.debthunter.controlplane.store.runs.RunStatus;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** The whole remediation path over in-memory parts, with the host and the clock under control. */
public final class RemediationFixture {

  public static final String REPO = "acme/widgets";

  public final MutableClock clock = new MutableClock();
  public final InMemoryWorkQueue queue =
      new InMemoryWorkQueue(
          RemediationQueue.policies(
              3, Duration.ofMinutes(5), Duration.ofSeconds(1), Duration.ofSeconds(4)),
          clock);
  public final FakeSourceHost host = new FakeSourceHost().withBranch("main", "sha-main");
  public final InMemoryPseudonymisedFindingStore findings = new InMemoryPseudonymisedFindingStore();
  public final InMemoryRunRepository runs = new InMemoryRunRepository();
  public final Set<String> enabledRepositories = new HashSet<>(Set.of(REPO, "acme/other"));
  public volatile boolean enablementDown;
  public RemediationAdvisor advisor = new RuleBasedRemediationAdvisor(clock);
  public final RemediationRateLimit limit =
      new RemediationRateLimit(2, Duration.ofDays(1), Duration.ofHours(1));
  public final RemediationRequestService requests;
  public final RemediationWorker worker;

  public RemediationFixture() {
    RemediationEnabledLookup enabled =
        repository -> {
          if (enablementDown) {
            throw new IllegalStateException("registry unavailable");
          }
          return enabledRepositories.contains(repository);
        };
    FixAgentPrCreator creator =
        new FixAgentPrCreator(
            host, runId -> java.util.Optional.of("https://cp.example/runs/" + runId));
    requests = new RemediationRequestService(enabled, runs, findings, queue);
    worker =
        new RemediationWorker(
            queue,
            enabled,
            findings,
            request -> advisor.propose(request),
            creator,
            limit,
            clock,
            "w1",
            10,
            Duration.ofMillis(10));
  }

  public static PseudonymisedFinding finding(int i) {
    return AiFixtures.finding(
        "codemaat.hotspot:src/F" + i + ".java",
        "codemaat.hotspot",
        Category.HOTSPOT,
        Severity.HIGH,
        "src/F" + i + ".java",
        "src/F" + i + ".java is a change hotspot (" + (20 + i) + " revisions)",
        Map.of("changeFrequency", 20.0 + i, "engine", "code-maat"),
        "fp-" + i);
  }

  /** Stores a run in a repository holding the given findings. */
  public RemediationFixture storeRun(String runId, String repository, PseudonymisedFinding... fs) {
    runs.save(
        new RunRecord(
            runId,
            repository,
            "default",
            "c-" + runId,
            "0.1.0",
            "k-" + runId,
            false,
            false,
            List.of(new EngineRecord("code-maat", "OK")),
            AiFixtures.NOW,
            RunStatus.ACTIVE,
            null));
    findings.saveRun(runId, repository, List.of(fs));
    return this;
  }

  public RequestOutcome request(String repository, String runId, PseudonymisedFinding f) {
    return requests.request(repository, runId, f.id(), "main", "alice-the-lead");
  }
}
