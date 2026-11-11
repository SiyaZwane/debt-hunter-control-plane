package com.debthunter.controlplane.ingest.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.lifecycle.FindingStateRecord;
import com.debthunter.controlplane.store.lifecycle.FindingTransitionEvent;
import com.debthunter.controlplane.store.lifecycle.TransitionCause;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The state machine, rule by rule, with no store, queue or clock involved. */
class FindingLifecycleResolverTest {

  private static final String REPO = "acme/widgets";
  private static final String SLICE = "default";
  private static final Instant D1 = LifecycleFixtures.day(1);
  private static final Instant D2 = LifecycleFixtures.day(2);

  private final InMemorySuppressionLookup suppressions = new InMemorySuppressionLookup();
  private final FindingLifecycleResolver resolver = new FindingLifecycleResolver(suppressions);

  private static Map<String, FindingStateRecord> states(Object... pairs) {
    Map<String, FindingStateRecord> map = new HashMap<>();
    for (int i = 0; i < pairs.length; i += 3) {
      String fp = (String) pairs[i];
      map.put(
          fp,
          new FindingStateRecord(
              REPO, SLICE, fp, (FindingState) pairs[i + 1], (String) pairs[i + 2]));
    }
    return map;
  }

  private ResolutionResult run(
      String runId,
      String commit,
      Instant date,
      boolean complete,
      Set<String> observed,
      Map<String, FindingStateRecord> current) {
    return resolver.resolve(REPO, SLICE, runId, commit, date, complete, observed, current);
  }

  private static List<FindingState> toStates(ResolutionResult result) {
    return result.events().stream().map(FindingTransitionEvent::toState).toList();
  }

  // ---- CP-AC-14, 15: new then open --------------------------------------------------------------

  @Test
  void cpAc14_aFindingSeenForTheFirstTimeIsNew() {
    ResolutionResult result = run("r1", "c1", D1, true, Set.of("fp-a"), Map.of());

    assertThat(result.events())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.fromState()).isNull();
              assertThat(e.toState()).isEqualTo(FindingState.NEW);
              assertThat(e.cause()).isEqualTo(TransitionCause.FIRST_OBSERVED);
            });
  }

  @Test
  void cpAc15_aFindingSeenAgainAtALaterCommitIsOpen() {
    ResolutionResult result =
        run("r2", "c2", D2, true, Set.of("fp-a"), states("fp-a", FindingState.NEW, "c1"));

    assertThat(toStates(result)).containsExactly(FindingState.OPEN);
    assertThat(result.events().get(0).cause()).isEqualTo(TransitionCause.OBSERVED_AGAIN);
  }

  @Test
  void anOpenFindingStillObservedProducesNoEventButRecordsTheNewerCommit() {
    ResolutionResult result =
        run("r3", "c3", D2, true, Set.of("fp-a"), states("fp-a", FindingState.OPEN, "c2"));

    assertThat(result.events()).isEmpty();
    assertThat(result.updatedStates())
        .singleElement()
        .satisfies(r -> assertThat(r.lastObservedCommit()).isEqualTo("c3"));
  }

  @Test
  void seeingANewFindingAgainAtTheSameCommitIsAReRunNotALaterObservation() {
    ResolutionResult result =
        run("r1b", "c1", D1, true, Set.of("fp-a"), states("fp-a", FindingState.NEW, "c1"));

    assertThat(result.events()).isEmpty();
    assertThat(result.updatedStates()).isEmpty();
  }

  // ---- CP-AC-16, 17: resolution ----------------------------------------------------------------

  @Test
  void cpAc16_aFindingAbsentFromACompleteRunIsResolved() {
    ResolutionResult result =
        run("r3", "c3", D2, true, Set.of(), states("fp-a", FindingState.OPEN, "c2"));

    assertThat(result.events())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.fromState()).isEqualTo(FindingState.OPEN);
              assertThat(e.toState()).isEqualTo(FindingState.RESOLVED);
              assertThat(e.cause()).isEqualTo(TransitionCause.ABSENT_FROM_COMPLETE_RUN);
            });
  }

  @Test
  void cpAc17_aDegradedRunNeverResolvesAnything() {
    Map<String, FindingStateRecord> current =
        states(
            "fp-new",
            FindingState.NEW,
            "c1",
            "fp-open",
            FindingState.OPEN,
            "c1",
            "fp-regressed",
            FindingState.REGRESSED,
            "c1");

    ResolutionResult result = run("r4", "c4", D2, false, Set.of(), current);

    assertThat(result.events()).isEmpty();
    assertThat(result.updatedStates()).isEmpty();
  }

  @Test
  void cpAc17_aDegradedRunStillRecordsWhatItDidSee() {
    ResolutionResult result = run("r4", "c4", D2, false, Set.of("fp-a"), Map.of());

    assertThat(toStates(result)).containsExactly(FindingState.NEW);
  }

  // ---- CP-AC-18: regression --------------------------------------------------------------------

  @Test
  void cpAc18_aResolvedFindingThatReturnsIsRegressedNotNew() {
    ResolutionResult result =
        run("r5", "c5", D2, true, Set.of("fp-a"), states("fp-a", FindingState.RESOLVED, "c2"));

    assertThat(toStates(result)).containsExactly(FindingState.REGRESSED);
    assertThat(result.events().get(0).cause())
        .isEqualTo(TransitionCause.REAPPEARED_AFTER_RESOLUTION);
  }

  @Test
  void aRegressedFindingSeenAgainBecomesOpen() {
    ResolutionResult result =
        run("r6", "c6", D2, true, Set.of("fp-a"), states("fp-a", FindingState.REGRESSED, "c5"));

    assertThat(toStates(result)).containsExactly(FindingState.OPEN);
  }

  @Test
  void aResolvedFindingStillAbsentProducesNothing() {
    ResolutionResult result =
        run("r7", "c7", D2, true, Set.of(), states("fp-a", FindingState.RESOLVED, "c2"));

    assertThat(result.events()).isEmpty();
  }

  // ---- CP-AC-19, 20: suppression ---------------------------------------------------------------

  @Test
  void cpAc19_anActivelySuppressedFindingBecomesSuppressedAndStopsBlockingGating() {
    suppressions.suppress(REPO, "fp-a", LifecycleFixtures.day(30));

    ResolutionResult result =
        run("r8", "c8", D2, true, Set.of("fp-a"), states("fp-a", FindingState.OPEN, "c7"));

    assertThat(toStates(result)).containsExactly(FindingState.SUPPRESSED);
    assertThat(FindingState.SUPPRESSED.blocksGating()).isFalse();
  }

  @Test
  void aFirstObservationUnderSuppressionPassesThroughNewBecauseTheMachineSaysSo() {
    suppressions.suppress(REPO, "fp-a", LifecycleFixtures.day(30));

    ResolutionResult result = run("r8", "c8", D2, true, Set.of("fp-a"), Map.of());

    assertThat(toStates(result)).containsExactly(FindingState.NEW, FindingState.SUPPRESSED);
  }

  @Test
  void aSuppressedFindingAbsentFromACompleteRunStaysSuppressed() {
    suppressions.suppress(REPO, "fp-a", LifecycleFixtures.day(30));

    ResolutionResult result =
        run("r9", "c9", D2, true, Set.of(), states("fp-a", FindingState.SUPPRESSED, "c8"));

    assertThat(result.events()).isEmpty();
  }

  @Test
  void cpAc20_aSuppressionExpiredAsOfTheCommitDateReturnsTheFindingToOpen() {
    suppressions.suppress(REPO, "fp-a", LifecycleFixtures.day(1));

    ResolutionResult result =
        run("r10", "c10", D2, true, Set.of("fp-a"), states("fp-a", FindingState.SUPPRESSED, "c8"));

    assertThat(result.events())
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.toState()).isEqualTo(FindingState.OPEN);
              assertThat(e.cause()).isEqualTo(TransitionCause.SUPPRESSION_EXPIRED);
            });
  }

  @Test
  void cpAc20_expiryIsJudgedByTheCommitDateNotTheWallClock() {
    // The suppression expires on day 10. Wall-clock "now" is long past that, but the commit being
    // analysed is dated day 2, so the suppression still applies at that point in history.
    suppressions.suppress(REPO, "fp-a", LifecycleFixtures.day(10));

    ResolutionResult result =
        run("r11", "c11", D2, true, Set.of("fp-a"), states("fp-a", FindingState.SUPPRESSED, "c8"));

    assertThat(result.events()).isEmpty();
  }

  @Test
  void aSuppressionRemovedEntirelyAlsoReturnsTheFindingToOpen() {
    ResolutionResult result =
        run("r12", "c12", D2, true, Set.of("fp-a"), states("fp-a", FindingState.SUPPRESSED, "c8"));

    assertThat(toStates(result)).containsExactly(FindingState.OPEN);
  }

  @Test
  void anExpiredSuppressionOnAnAbsentFindingReopensThenResolvesIt() {
    suppressions.suppress(REPO, "fp-a", LifecycleFixtures.day(1));

    ResolutionResult result =
        run("r13", "c13", D2, true, Set.of(), states("fp-a", FindingState.SUPPRESSED, "c8"));

    assertThat(toStates(result)).containsExactly(FindingState.OPEN, FindingState.RESOLVED);
  }

  // ---- machine-wide properties -----------------------------------------------------------------

  @Test
  void everyEventIsTimestampedWithTheCommitDate() {
    ResolutionResult result = run("r1", "c1", D1, true, Set.of("fp-a"), Map.of());

    assertThat(result.events()).allSatisfy(e -> assertThat(e.timestamp()).isEqualTo(D1));
  }

  @Test
  void eventIdsAreDeterministicSoARetriedRunProducesTheSameIds() {
    ResolutionResult first = run("r1", "c1", D1, true, Set.of("fp-a", "fp-b"), Map.of());
    ResolutionResult second = run("r1", "c1", D1, true, Set.of("fp-a", "fp-b"), Map.of());

    assertThat(second.events()).isEqualTo(first.events());
  }

  @Test
  void eventsAreOrderedByFingerprintSoOutputDoesNotDependOnSetIterationOrder() {
    ResolutionResult result = run("r1", "c1", D1, true, Set.of("fp-c", "fp-a", "fp-b"), Map.of());

    assertThat(result.events())
        .extracting(FindingTransitionEvent::fingerprint)
        .containsExactly("fp-a", "fp-b", "fp-c");
  }

  @Test
  void theStateMachineForbidsMovesOutsideItsTable() {
    for (FindingState from : FindingState.values()) {
      for (FindingState to : FindingState.values()) {
        boolean allowed = from.canMoveTo(to);
        if (from == FindingState.RESOLVED) {
          assertThat(allowed).as(from + "->" + to).isEqualTo(to == FindingState.REGRESSED);
        }
        if (to == FindingState.NEW) {
          assertThat(allowed).as(from + "->NEW").isFalse();
        }
      }
    }
  }
}
