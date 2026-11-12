package com.debthunter.controlplane.serve.query;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.findings.JdbcPseudonymisedFindingStore;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.identity.Pseudonym;
import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.lifecycle.FindingStateRecord;
import com.debthunter.controlplane.store.lifecycle.JdbcFindingStateRepository;
import com.debthunter.controlplane.store.runs.EngineRecord;
import com.debthunter.controlplane.store.runs.JdbcRunRepository;
import com.debthunter.controlplane.store.runs.RunRecord;
import com.debthunter.controlplane.store.runs.RunStatus;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import com.debthunter.domain.Severity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class JdbcFindingQueryRepositoryTest extends FindingQueryRepositoryContract {

  private DataSource ds;

  @Override
  protected FindingQueryRepository newRepository() {
    ds = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(ds);
    return new JdbcFindingQueryRepository(ds);
  }

  @Override
  protected void seed(FindingRow... rows) {
    for (FindingRow r : rows) {
      run(r.runId(), r.repository(), r.projectSlice(), r.observedAt());
      new JdbcPseudonymisedFindingStore(ds).saveRun(r.runId(), r.repository(), List.of(finding(r)));
      new JdbcFindingStateRepository(ds)
          .putAll(
              List.of(
                  new FindingStateRecord(
                      r.repository(), r.projectSlice(), r.fingerprint(), r.state(), "c")));
    }
  }

  private void run(String runId, String repository, String slice, Instant at) {
    new JdbcRunRepository(ds)
        .save(
            new RunRecord(
                runId,
                repository,
                slice,
                "c-" + runId,
                "0.1.0",
                "k-" + runId,
                false,
                false,
                List.of(new EngineRecord("code-maat", "OK")),
                at,
                RunStatus.ACTIVE,
                null,
                "PASSED",
                at,
                "FULL",
                "1.0"));
  }

  private static PseudonymisedFinding finding(FindingRow r) {
    return new PseudonymisedFinding(
        r.findingId(),
        r.ruleId(),
        r.category(),
        r.severity(),
        0.8,
        r.path(),
        r.startLine(),
        r.message(),
        Map.of(),
        0.5,
        true,
        r.fingerprint(),
        List.of(Pseudonym.fromStored("c".repeat(64))));
  }

  @Test
  void aFindingTakesItsDetailsFromTheLatestRunThatReportedIt() {
    Instant t0 = Instant.parse("2026-07-01T00:00:00Z");
    FindingRow older =
        new FindingRow(
            "acme/a",
            "root",
            "fp",
            FindingState.OPEN,
            "f1",
            "rule",
            com.debthunter.domain.Category.HOTSPOT,
            Severity.LOW,
            "src/A.java",
            3,
            "old",
            "run-old",
            t0);
    seed(older);
    run("run-new", "acme/a", "root", t0.plus(Duration.ofDays(1)));
    new JdbcPseudonymisedFindingStore(ds)
        .saveRun(
            "run-new",
            "acme/a",
            List.of(
                finding(
                    new FindingRow(
                        "acme/a",
                        "root",
                        "fp",
                        FindingState.OPEN,
                        "f9",
                        "rule",
                        com.debthunter.domain.Category.HOTSPOT,
                        Severity.CRITICAL,
                        "src/A.java",
                        4,
                        "new",
                        "run-new",
                        t0))));

    Page<FindingRow> page =
        repo.find(
            new FindingFilter(Set.of("acme/a"), null, null, null, null, null),
            PageRequest.of(null, null));

    assertThat(page.items()).hasSize(1);
    assertThat(page.items().get(0).severity()).isEqualTo(Severity.CRITICAL);
    assertThat(page.items().get(0).runId()).isEqualTo("run-new");
    assertThat(page.items().get(0).message()).isEqualTo("new");
    assertThat(repo.openCounts(Set.of("acme/a")).get("acme/a")).containsOnlyKeys(Severity.CRITICAL);
  }

  @Test
  void asOfIsWhenTheNewestRunWasReceived() {
    Instant t0 = Instant.parse("2026-07-01T00:00:00Z");
    run("r1", "acme/a", "root", t0);
    run("r2", "acme/a", "root", t0.plusSeconds(500));

    assertThat(repo.asOf()).contains(t0.plusSeconds(500));
  }

  @Test
  void aFindingWithNoRunOnRecordIsNotListedRatherThanBreakingTheQuery() {
    new JdbcFindingStateRepository(ds)
        .putAll(
            List.of(new FindingStateRecord("acme/a", "root", "orphan", FindingState.OPEN, "c")));

    assertThat(
            repo.find(
                    new FindingFilter(Set.of("acme/a"), null, null, null, null, null),
                    PageRequest.of(null, null))
                .items())
        .isEmpty();
  }
}
