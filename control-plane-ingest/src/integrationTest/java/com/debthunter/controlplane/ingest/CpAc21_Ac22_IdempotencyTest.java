package com.debthunter.controlplane.ingest;

import static com.debthunter.controlplane.ingest.LifecycleScenario.REPO;
import static com.debthunter.controlplane.ingest.LifecycleScenario.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.runs.RunStatus;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CP-AC-21 and CP-AC-22: the idempotency guard, through the real pipeline. */
@Tag("integration")
class CpAc21_Ac22_IdempotencyTest {

  @Test
  void cpAc21_aDuplicateRunIdIsDiscardedEvenAfterARestartForgotTheReceiversMemory()
      throws Exception {
    IngestPipeline.Stores stores = new IngestPipeline.Stores();
    String bundle = run("r1", "c1").observing("fp-a").build();

    try (LifecycleScenario first = new LifecycleScenario(stores)) {
      first.pipeline.publish(bundle);
      first.pipeline.awaitRecorded("r1");
    }
    int eventsAfterFirst = stores.transitions.eventsOfRun("r1").size();

    // A fresh pipeline has an empty in-memory de-duplicator, as after a restart. The persisted run
    // store is what must recognise the redelivery.
    try (LifecycleScenario second = new LifecycleScenario(stores)) {
      second.pipeline.publish(bundle);
      second.pipeline.publish(run("sentinel", "c2").observing("fp-a").build());
      second.pipeline.awaitRecorded("sentinel");

      assertThat(stores.transitions.eventsOfRun("r1")).hasSize(eventsAfterFirst);
      assertThat(second.stateOf("fp-a")).isEqualTo(FindingState.OPEN); // only the sentinel moved it
    }
  }

  @Test
  void cpAc22_aRerunOfTheSameCommitSupersedesTheFirstAndBothAreRetained() throws Exception {
    try (LifecycleScenario s = new LifecycleScenario()) {
      s.apply(run("r1", "c1").observing("fp-a"));
      s.apply(run("r1-rerun", "c1").observing("fp-a"));

      assertThat(s.pipeline.runs.find("r1"))
          .get()
          .satisfies(
              r -> {
                assertThat(r.status()).isEqualTo(RunStatus.SUPERSEDED);
                assertThat(r.supersededBy()).isEqualTo("r1-rerun");
              });
      assertThat(s.pipeline.runs.find("r1-rerun").get().status()).isEqualTo(RunStatus.ACTIVE);
      assertThat(s.pipeline.runs.latestApplied(REPO, "default").get().runId())
          .isEqualTo("r1-rerun");
    }
  }

  @Test
  void cpAc22_aRerunDoesNotCountAsASecondObservationSoNewDoesNotBecomeOpen() throws Exception {
    try (LifecycleScenario s = new LifecycleScenario()) {
      s.apply(run("r1", "c1").observing("fp-a"));
      s.apply(run("r1-rerun", "c1").observing("fp-a"));

      assertThat(s.stateOf("fp-a")).isEqualTo(FindingState.NEW);
    }
  }

  @Test
  void aQuarantinedRunIdIsNotSpentSoACorrectedBundleUnderTheSameIdIsProcessed() throws Exception {
    try (LifecycleScenario s = new LifecycleScenario()) {
      String broken = run("r-fix", "c1").observing("fp-a").timestamp("not a time").build();
      s.pipeline.publish(broken);
      org.awaitility.Awaitility.await()
          .atMost(java.time.Duration.ofSeconds(60))
          .until(() -> s.pipeline.quarantinedRunIds.contains("r-fix"));

      s.apply(run("r-fix", "c1").observing("fp-a"));

      assertThat(s.stateOf("fp-a")).isEqualTo(FindingState.NEW);
    }
  }
}
