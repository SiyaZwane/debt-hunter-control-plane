package com.debthunter.controlplane.ingest;

import static com.debthunter.controlplane.ingest.LifecycleScenario.REPO;
import static com.debthunter.controlplane.ingest.LifecycleScenario.SLICE;
import static com.debthunter.controlplane.ingest.LifecycleScenario.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.lifecycle.FindingTransitionEvent;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The system of record is the point of CP-FR-05: with real Kafka in front and real PostgreSQL
 * behind, a process that restarts mid-history carries on from where the data says it was.
 */
@Tag("integration")
class DurableIngestSurvivesRestartTest {

  private static DataSource migrated() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return dataSource;
  }

  @Test
  void lifecycleStateAndHistoryCarryOnAcrossARestart() throws Exception {
    DataSource dataSource = migrated();

    try (LifecycleScenario before = new LifecycleScenario(new IngestPipeline.Stores(dataSource))) {
      before.apply(run("r1", "c1").observing("fp-a"));
    }

    // A new process: new pipeline, new receiver memory, nothing carried over but the database.
    try (LifecycleScenario after = new LifecycleScenario(new IngestPipeline.Stores(dataSource))) {
      assertThat(after.stateOf("fp-a")).isEqualTo(FindingState.NEW);

      after.apply(run("r2", "c2").observing("fp-a"));

      assertThat(after.stateOf("fp-a")).isEqualTo(FindingState.OPEN);
      assertThat(after.historyOf("fp-a"))
          .extracting(FindingTransitionEvent::toState)
          .containsExactly(FindingState.NEW, FindingState.OPEN);
    }
  }

  @Test
  void aRedeliveryAfterARestartIsRecognisedFromTheDatabaseAlone() throws Exception {
    DataSource dataSource = migrated();
    String bundle = run("r1", "c1").observing("fp-a").build();

    try (LifecycleScenario before = new LifecycleScenario(new IngestPipeline.Stores(dataSource))) {
      before.pipeline.publish(bundle);
      before.pipeline.awaitRecorded("r1");
    }

    try (LifecycleScenario after = new LifecycleScenario(new IngestPipeline.Stores(dataSource))) {
      after.pipeline.publish(bundle);
      after.pipeline.publish(run("sentinel", "c2").observing("fp-a").build());
      after.pipeline.awaitRecorded("sentinel");

      assertThat(after.pipeline.transitions.eventsOfRun("r1")).hasSize(1);
      assertThat(after.pipeline.runs.latestApplied(REPO, SLICE).get().runId())
          .isEqualTo("sentinel");
    }
  }

  @Test
  void aDegradedRunsFlagAndEngineStatusAreInTheDatabaseNotJustInMemory() throws Exception {
    DataSource dataSource = migrated();

    try (LifecycleScenario s = new LifecycleScenario(new IngestPipeline.Stores(dataSource))) {
      s.apply(run("r1", "c1").observing("fp-a"));
      s.apply(run("r2", "c2").degraded());
    }

    var restored =
        new com.debthunter.controlplane.store.runs.JdbcRunRepository(dataSource).find("r2");
    assertThat(restored)
        .get()
        .satisfies(
            r -> {
              assertThat(r.degraded()).isTrue();
              assertThat(r.engines()).extracting(e -> e.status()).containsExactly("FAILED");
            });
  }
}
