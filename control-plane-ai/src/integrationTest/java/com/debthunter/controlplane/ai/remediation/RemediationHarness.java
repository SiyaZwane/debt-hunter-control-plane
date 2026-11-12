package com.debthunter.controlplane.ai.remediation;

import com.debthunter.controlplane.ai.AiFixtures;
import com.debthunter.controlplane.ai.MutableClock;
import com.debthunter.controlplane.ai.remediation.host.FakeGitHubServer;
import com.debthunter.controlplane.ai.remediation.host.GitHubSourceHostClient;
import com.debthunter.controlplane.store.findings.JdbcPseudonymisedFindingStore;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.store.queue.JdbcWorkQueue;
import com.debthunter.controlplane.store.runs.EngineRecord;
import com.debthunter.controlplane.store.runs.JdbcRunRepository;
import com.debthunter.controlplane.store.runs.RunRecord;
import com.debthunter.controlplane.store.runs.RunStatus;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;

/**
 * Request service, durable queue, worker and GitHub adapter over real PostgreSQL and a fake GitHub.
 */
final class RemediationHarness implements AutoCloseable {

  static final String REPO = "acme/widgets";

  final FakeGitHubServer github = new FakeGitHubServer().withBranch("main", "sha-main");
  final MutableClock clock = new MutableClock();
  final DataSource dataSource = PostgresTestDatabase.freshSchema();
  final Set<String> enabled = new HashSet<>(Set.of(REPO));
  final JdbcWorkQueue queue;
  final JdbcRunRepository runs;
  final JdbcPseudonymisedFindingStore findings;
  final RemediationRequestService requests;
  final RemediationWorker worker;

  RemediationHarness() {
    StoreMigrations.migrate(dataSource);
    queue =
        new JdbcWorkQueue(
            dataSource,
            RemediationQueue.policies(
                3, Duration.ofMinutes(5), Duration.ofSeconds(1), Duration.ofSeconds(4)),
            clock);
    runs = new JdbcRunRepository(dataSource);
    findings = new JdbcPseudonymisedFindingStore(dataSource);
    RemediationEnabledLookup lookup = enabled::contains;
    requests = new RemediationRequestService(lookup, runs, findings, queue);
    FixAgentPrCreator creator =
        new FixAgentPrCreator(
            new GitHubSourceHostClient(github.uri(), () -> "test-token", Duration.ofSeconds(5)),
            runId -> Optional.of("https://cp.example/runs/" + runId));
    RemediationAdvisor advisor = new RuleBasedRemediationAdvisor(clock);
    worker =
        new RemediationWorker(
            queue,
            lookup,
            findings,
            advisor::propose,
            creator,
            new RemediationRateLimit(5, Duration.ofDays(1), Duration.ofHours(1)),
            clock,
            "w1",
            10,
            Duration.ofMillis(10));
  }

  RemediationHarness storeRun(String runId, PseudonymisedFinding... fs) {
    runs.save(
        new RunRecord(
            runId,
            REPO,
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
    findings.saveRun(runId, REPO, List.of(fs));
    return this;
  }

  @Override
  public void close() {
    github.close();
  }
}
