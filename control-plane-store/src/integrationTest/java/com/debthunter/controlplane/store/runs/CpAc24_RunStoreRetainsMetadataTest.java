package com.debthunter.controlplane.store.runs;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-24: the run store retains each run's metadata — including the degraded flag and every
 * engine's status — durably.
 */
@Tag("integration")
class CpAc24_RunStoreRetainsMetadataTest {

  private static final Instant COMMITTED = Instant.parse("2026-01-01T09:30:00Z");

  @Test
  void cpAc24_aRunsMetadataIsRetainedAcrossARestart() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    new JdbcRunRepository(dataSource)
        .save(
            new RunRecord(
                "run-1",
                "acme/widgets",
                "api",
                "c1",
                "0.1.0",
                "key-1",
                true,
                false,
                List.of(new EngineRecord("code-maat", "OK"), new EngineRecord("sonar", "FAILED")),
                COMMITTED,
                RunStatus.ACTIVE,
                null));

    RunRecord restored = new JdbcRunRepository(dataSource).find("run-1").orElseThrow();

    assertThat(restored.degraded()).isTrue();
    assertThat(restored.engines())
        .containsExactly(new EngineRecord("code-maat", "OK"), new EngineRecord("sonar", "FAILED"));
    assertThat(restored.commit()).isEqualTo("c1");
    assertThat(restored.projectSlice()).isEqualTo("api");
    assertThat(restored.committedAt()).isEqualTo(COMMITTED);
    assertThat(restored.status()).isEqualTo(RunStatus.ACTIVE);
  }

  @Test
  void cpAc24_aSupersededRunIsRetainedNotDeletedAndSaysWhatReplacedIt() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    JdbcRunRepository runs = new JdbcRunRepository(dataSource);
    runs.save(run("run-1", RunStatus.ACTIVE));
    runs.save(run("run-1b", RunStatus.ACTIVE));

    RunRecord first = new JdbcRunRepository(dataSource).find("run-1").orElseThrow();

    assertThat(first.status()).isEqualTo(RunStatus.SUPERSEDED);
    assertThat(first.supersededBy()).isEqualTo("run-1b");
    assertThat(new JdbcRunRepository(dataSource).findByCompositeKey("key-1")).hasSize(2);
  }

  @Test
  void cpAc24_aRunWhoseEngineListFailsToWriteLeavesNoHalfWrittenRun() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    JdbcRunRepository runs = new JdbcRunRepository(dataSource);
    RunRecord bad =
        new RunRecord(
            "run-bad",
            "acme/widgets",
            "default",
            "c1",
            "0.1.0",
            "key-1",
            false,
            false,
            List.of(new EngineRecord("code-maat", null)),
            COMMITTED,
            RunStatus.ACTIVE,
            null);

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> runs.save(bad))
        .isInstanceOf(com.debthunter.controlplane.store.jdbc.StoreException.class);

    assertThat(runs.find("run-bad")).isEmpty(); // rolled back: no run without its engines
  }

  private static RunRecord run(String id, RunStatus status) {
    return new RunRecord(
        id,
        "acme/widgets",
        "default",
        "c1",
        "0.1.0",
        "key-1",
        false,
        false,
        List.of(),
        COMMITTED,
        status,
        null);
  }
}
