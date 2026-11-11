package com.debthunter.controlplane.ingest;

import static com.debthunter.controlplane.ingest.LifecycleScenario.REPO;
import static com.debthunter.controlplane.ingest.LifecycleScenario.SLICE;
import static com.debthunter.controlplane.ingest.LifecycleScenario.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.lifecycle.FindingTransitionEvent;
import com.debthunter.controlplane.store.lifecycle.TransitionCause;
import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-14 to CP-AC-20: the finding lifecycle, driven by real bundles through the real queue,
 * listener, normaliser, pseudonymiser and lifecycle stage.
 */
@Tag("integration")
class CpAc14_To_Ac20_FindingLifecycleTest {

  @Test
  void cpAc14_firstObservationIsNew() throws Exception {
    try (LifecycleScenario s = new LifecycleScenario()) {
      s.apply(run("r1", "c1").observing("fp-a"));

      assertThat(s.stateOf("fp-a")).isEqualTo(FindingState.NEW);
      assertThat(s.historyOf("fp-a"))
          .singleElement()
          .satisfies(e -> assertThat(e.cause()).isEqualTo(TransitionCause.FIRST_OBSERVED));
    }
  }

  @Test
  void cpAc15_secondObservationAtALaterCommitIsOpen() throws Exception {
    try (LifecycleScenario s = new LifecycleScenario()) {
      s.apply(run("r1", "c1").observing("fp-a"));
      s.apply(run("r2", "c2").observing("fp-a"));

      assertThat(s.stateOf("fp-a")).isEqualTo(FindingState.OPEN);
    }
  }

  @Test
  void cpAc16_aCompleteRunWithoutTheFindingResolvesIt() throws Exception {
    try (LifecycleScenario s = new LifecycleScenario()) {
      s.apply(run("r1", "c1").observing("fp-a"));
      s.apply(run("r2", "c2").observing("fp-a"));
      s.apply(run("r3", "c3"));

      assertThat(s.stateOf("fp-a")).isEqualTo(FindingState.RESOLVED);
    }
  }

  @Test
  void cpAc17_aDegradedRunDoesNotResolveAndNoResolvedEventIsEverEmitted() throws Exception {
    try (LifecycleScenario s = new LifecycleScenario()) {
      s.apply(run("r1", "c1").observing("fp-a"));
      s.apply(run("r2", "c2").observing("fp-a"));
      // The engine that produces fp-a failed, so fp-a is absent. That absence proves nothing.
      s.apply(run("r3", "c3").degraded());

      assertThat(s.stateOf("fp-a")).isEqualTo(FindingState.OPEN);
      assertThat(s.historyOf("fp-a"))
          .extracting(FindingTransitionEvent::toState)
          .doesNotContain(FindingState.RESOLVED);
      assertThat(s.pipeline.runs.find("r3"))
          .get()
          .satisfies(
              r -> {
                assertThat(r.degraded()).isTrue();
                assertThat(r.engines()).extracting(e -> e.status()).containsExactly("FAILED");
              });

      // The next complete run, which really could have seen it, does resolve it.
      s.apply(run("r4", "c4"));
      assertThat(s.stateOf("fp-a")).isEqualTo(FindingState.RESOLVED);
    }
  }

  @Test
  void cpAc18_aResolvedFindingThatReturnsIsRegressedAndNotNewAgain() throws Exception {
    try (LifecycleScenario s = new LifecycleScenario()) {
      s.apply(run("r1", "c1").observing("fp-a"));
      s.apply(run("r2", "c2"));
      s.apply(run("r3", "c3").observing("fp-a"));

      assertThat(s.stateOf("fp-a")).isEqualTo(FindingState.REGRESSED);
      assertThat(s.historyOf("fp-a"))
          .extracting(FindingTransitionEvent::toState)
          .containsExactly(FindingState.NEW, FindingState.RESOLVED, FindingState.REGRESSED);
    }
  }

  @Test
  void cpAc19_aSuppressedFindingIsExcludedFromGating() throws Exception {
    try (LifecycleScenario s = new LifecycleScenario()) {
      s.pipeline.suppressions.suppress(REPO, "fp-a", Instant.parse("2026-02-01T00:00:00Z"));

      s.apply(run("r1", "c1").observing("fp-a", "fp-b"));
      s.apply(run("r2", "c2").observing("fp-a", "fp-b"));

      assertThat(s.stateOf("fp-a")).isEqualTo(FindingState.SUPPRESSED);
      assertThat(s.pipeline.states.blockingFingerprints(REPO, SLICE)).containsExactly("fp-b");
    }
  }

  @Test
  void cpAc20_anExpiredSuppressionReturnsTheFindingToOpen() throws Exception {
    try (LifecycleScenario s = new LifecycleScenario()) {
      // Expires on 2026-01-03: after c1 (Jan 1) and c2 (Jan 2), before c4 (Jan 4).
      s.pipeline.suppressions.suppress(REPO, "fp-a", Instant.parse("2026-01-03T00:00:00Z"));

      s.apply(run("r1", "c1").observing("fp-a"));
      s.apply(run("r2", "c2").observing("fp-a"));
      assertThat(s.stateOf("fp-a")).isEqualTo(FindingState.SUPPRESSED);

      s.apply(run("r4", "c4").observing("fp-a"));

      assertThat(s.stateOf("fp-a")).isEqualTo(FindingState.OPEN);
      assertThat(s.historyOf("fp-a"))
          .extracting(FindingTransitionEvent::cause)
          .contains(TransitionCause.SUPPRESSION_EXPIRED);
      assertThat(s.pipeline.states.blockingFingerprints(REPO, SLICE)).containsExactly("fp-a");
    }
  }

  @Test
  void cpAc20_expiryFollowsTheCommitDateEvenThoughTheWallClockIsFarPastIt() throws Exception {
    try (LifecycleScenario s = new LifecycleScenario()) {
      // The suppression "expired" months ago by the wall clock, yet commit c2 is dated Jan 2.
      s.pipeline.suppressions.suppress(REPO, "fp-a", Instant.parse("2026-01-10T00:00:00Z"));

      s.apply(run("r1", "c1").observing("fp-a"));
      s.apply(run("r2", "c2").observing("fp-a"));

      assertThat(s.stateOf("fp-a")).isEqualTo(FindingState.SUPPRESSED);
    }
  }
}
