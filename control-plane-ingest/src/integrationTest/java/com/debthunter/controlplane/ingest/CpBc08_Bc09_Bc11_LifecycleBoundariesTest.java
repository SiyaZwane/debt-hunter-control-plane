package com.debthunter.controlplane.ingest;

import static com.debthunter.controlplane.ingest.LifecycleScenario.REPO;
import static com.debthunter.controlplane.ingest.LifecycleScenario.SLICE;
import static com.debthunter.controlplane.ingest.LifecycleScenario.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.debthunter.controlplane.ingest.pseudonymise.InMemoryPseudonymKeyStore;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.lifecycle.FindingTransitionEvent;
import com.debthunter.controlplane.store.lifecycle.TransitionLogRepository;
import com.debthunter.controlplane.store.runs.RunStatus;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CP-BC-08, CP-BC-09 and CP-BC-11, through the real pipeline. */
@Tag("integration")
class CpBc08_Bc09_Bc11_LifecycleBoundariesTest {

  @Test
  void cpBc08_aBundleForAnOlderCommitArrivingLateDoesNotUndoWhatANewerCommitSettled()
      throws Exception {
    try (LifecycleScenario s = new LifecycleScenario()) {
      s.apply(run("r1", "c1").observing("fp-a"));
      s.apply(run("r3", "c3")); // fp-a is fixed by c3 and resolved
      assertThat(s.stateOf("fp-a")).isEqualTo(FindingState.RESOLVED);

      // The bundle for c2, which sits between them and still had fp-a, arrives last. By arrival
      // order it is the newest; by the parent graph it is the past.
      s.apply(run("r2", "c2").observing("fp-a"));

      assertThat(s.stateOf("fp-a")).isEqualTo(FindingState.RESOLVED); // not REGRESSED
      assertThat(s.pipeline.runs.find("r2"))
          .get()
          .extracting(r -> r.status())
          .isEqualTo(RunStatus.LATE);
      assertThat(s.pipeline.runs.latestApplied(REPO, SLICE).get().runId()).isEqualTo("r3");
    }
  }

  @Test
  void cpBc08_theLateRunIsRetainedForTheRecord() throws Exception {
    try (LifecycleScenario s = new LifecycleScenario()) {
      s.apply(run("r3", "c3").observing("fp-a"));
      s.apply(run("r1", "c1").observing("fp-a"));

      assertThat(s.pipeline.runs.find("r1")).isPresent();
      assertThat(s.historyOf("fp-a")).hasSize(1); // the late run moved nothing
    }
  }

  @Test
  void cpBc09_aPullRequestRunCannotResolveFindingsOutsideItsScope() throws Exception {
    try (LifecycleScenario s = new LifecycleScenario()) {
      s.apply(run("r1", "c1").observing("fp-a", "fp-b"));
      s.apply(run("r2", "c2").observing("fp-a", "fp-b"));

      // The PR touches one file, so it reports only fp-a. fp-b is outside its scope, not fixed.
      s.apply(run("r-pr", "c3").pullRequest("c2", "42").observing("fp-a"));

      assertThat(s.stateOf("fp-b")).isEqualTo(FindingState.OPEN);
      assertThat(s.stateOf("fp-a")).isEqualTo(FindingState.OPEN);
      assertThat(s.pipeline.runs.find("r-pr"))
          .get()
          .satisfies(
              r -> {
                assertThat(r.status()).isEqualTo(RunStatus.SCOPE_LIMITED);
                assertThat(r.scopeLimited()).isTrue();
              });
    }
  }

  @Test
  void cpBc09_aPullRequestRunDoesNotBecomeThePointTheMainHistoryIsOrderedFrom() throws Exception {
    try (LifecycleScenario s = new LifecycleScenario()) {
      s.apply(run("r1", "c1").observing("fp-a"));
      s.apply(run("r-pr", "c5").pullRequest("c1", "7").observing("fp-a"));
      s.apply(run("r2", "c2").observing("fp-a"));

      assertThat(s.pipeline.runs.find("r2"))
          .get()
          .extracting(r -> r.status())
          .isEqualTo(RunStatus.ACTIVE);
      assertThat(s.stateOf("fp-a")).isEqualTo(FindingState.OPEN);
    }
  }

  @Test
  void cpBc11_whenTheTransitionLogCannotBeWrittenStateDoesNotMoveAndTheBundleIsHeld()
      throws Exception {
    IngestPipeline.Stores stores = new IngestPipeline.Stores();
    AtomicInteger attempts = new AtomicInteger();
    boolean[] logUp = {false};
    TransitionLogRepository flaky =
        new TransitionLogRepository() {
          @Override
          public void append(List<FindingTransitionEvent> events) {
            attempts.incrementAndGet();
            if (!logUp[0]) {
              throw new IllegalStateException("log store unavailable");
            }
            stores.transitions.append(events);
          }

          @Override
          public List<FindingTransitionEvent> historyOf(String r, String p, String f) {
            return stores.transitions.historyOf(r, p, f);
          }

          @Override
          public List<FindingTransitionEvent> eventsOfRun(String runId) {
            return stores.transitions.eventsOfRun(runId);
          }
        };

    try (IngestPipeline pipeline =
        new IngestPipeline(new InMemoryPseudonymKeyStore(new byte[32]), stores, flaky)) {
      stores.commits.add(REPO, "c1", java.time.Instant.parse("2026-01-01T00:00:00Z"));
      pipeline.publish(run("r1", "c1").observing("fp-a").build());

      await().atMost(Duration.ofSeconds(60)).until(() -> attempts.get() >= 3);
      // Through every failed attempt: no state moved without a record of why, and the run is not
      // recorded as applied, so it will be retried rather than lost.
      assertThat(stores.states.find(REPO, SLICE, "fp-a")).isEmpty();
      assertThat(stores.runs.find("r1")).isEmpty();

      logUp[0] = true;
      pipeline.awaitRecorded("r1");

      assertThat(stores.states.find(REPO, SLICE, "fp-a")).isPresent();
      assertThat(stores.transitions.historyOf(REPO, SLICE, "fp-a")).hasSize(1); // exactly once
    }
  }
}
