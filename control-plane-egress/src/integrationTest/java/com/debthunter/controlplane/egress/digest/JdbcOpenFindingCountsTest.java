package com.debthunter.controlplane.egress.digest;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.findings.JdbcPseudonymisedFindingStore;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.lifecycle.FindingStateRecord;
import com.debthunter.controlplane.store.lifecycle.JdbcFindingStateRepository;
import com.debthunter.controlplane.store.runs.EngineRecord;
import com.debthunter.controlplane.store.runs.JdbcRunRepository;
import com.debthunter.controlplane.store.runs.RunRecord;
import com.debthunter.controlplane.store.runs.RunStatus;
import com.debthunter.domain.Severity;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class JdbcOpenFindingCountsTest {

  private static final String REPO = "acme/widgets";

  private static void run(DataSource ds, String runId, String repo, String fp, Severity sev) {
    new JdbcRunRepository(ds)
        .save(
            new RunRecord(
                runId,
                repo,
                "root",
                "c-" + runId,
                "0.1.0",
                "k-" + runId,
                false,
                false,
                List.of(new EngineRecord("code-maat", "OK")),
                Instant.parse("2026-07-01T00:00:00Z"),
                RunStatus.ACTIVE,
                null));
    new JdbcPseudonymisedFindingStore(ds)
        .saveRun(runId, repo, List.of(DigestHarness.finding(fp, sev, "src/A.java", "m")));
  }

  private static void state(DataSource ds, String repo, String fp, FindingState state) {
    new JdbcFindingStateRepository(ds)
        .putAll(List.of(new FindingStateRecord(repo, "root", fp, state, "c")));
  }

  @Test
  void countsNewOpenAndRegressedBySeverityAndNothingElse() {
    DataSource ds = JdbcDigestParts.freshDatabase();
    run(ds, "r1", REPO, "fp-new", Severity.HIGH);
    run(ds, "r2", REPO, "fp-open", Severity.HIGH);
    run(ds, "r3", REPO, "fp-regressed", Severity.LOW);
    run(ds, "r4", REPO, "fp-resolved", Severity.CRITICAL);
    run(ds, "r5", REPO, "fp-suppressed", Severity.CRITICAL);
    run(ds, "r6", "other/thing", "fp-other", Severity.HIGH);
    state(ds, REPO, "fp-new", FindingState.NEW);
    state(ds, REPO, "fp-open", FindingState.OPEN);
    state(ds, REPO, "fp-regressed", FindingState.REGRESSED);
    state(ds, REPO, "fp-resolved", FindingState.RESOLVED);
    state(ds, REPO, "fp-suppressed", FindingState.SUPPRESSED);
    state(ds, "other/thing", "fp-other", FindingState.OPEN);

    assertThat(new JdbcOpenFindingCounts(ds).openBy(REPO))
        .containsEntry(Severity.HIGH, 2)
        .containsEntry(Severity.LOW, 1)
        .hasSize(2);
  }

  @Test
  void usesTheSeverityFromTheLatestRunThatReportedTheFinding() {
    DataSource ds = JdbcDigestParts.freshDatabase();
    run(ds, "r1", REPO, "fp-1", Severity.LOW);
    run(ds, "r2", REPO, "fp-1", Severity.CRITICAL);
    state(ds, REPO, "fp-1", FindingState.OPEN);

    assertThat(new JdbcOpenFindingCounts(ds).openBy(REPO)).containsOnlyKeys(Severity.CRITICAL);
  }

  @Test
  void aRepositoryWithNothingOpenCountsNothing() {
    assertThat(new JdbcOpenFindingCounts(JdbcDigestParts.freshDatabase()).openBy(REPO)).isEmpty();
  }
}
