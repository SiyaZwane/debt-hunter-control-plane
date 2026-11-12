package com.debthunter.controlplane.egress.workitem;

import static com.debthunter.controlplane.egress.workitem.WorkItemHarness.REPO;
import static com.debthunter.controlplane.egress.workitem.WorkItemHarness.SLICE;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.egress.outbox.DeliveryUnavailableException;
import com.debthunter.controlplane.egress.outbox.UndeliverableException;
import com.debthunter.controlplane.store.rollout.RolloutStage;
import com.debthunter.domain.Severity;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** CP-AC-63 to CP-AC-69: the decisions that keep a finding and its work item in line. */
class WorkItemOrchestratorTest {

  private static final String FP = "fp-1";

  private WorkItemHarness h;

  @BeforeEach
  void setUp() {
    h = InMemoryStores.harness();
    h.run("run-1", WorkItemHarness.finding(FP, Severity.HIGH));
  }

  private SyncResult reconcile() {
    return h.orchestrator.reconcile(new SyncJob(REPO, SLICE, FP, "run-1", "e"));
  }

  private void open() {
    h.firstSeen(FP, "run-1");
    h.opened(FP, "run-1");
  }

  // ---- CP-AC-63 ---------------------------------------------------------------------------

  @Test
  void anOpenFindingAtStageThreeGetsOneItem() {
    open();

    assertThat(reconcile()).isEqualTo(SyncResult.CREATED);

    assertThat(h.tracker.all()).hasSize(1);
    FakeTracker.Item item = h.tracker.all().get(0);
    assertThat(item.status).isEqualTo(WorkItemStatus.OPEN);
    assertThat(item.route.target()).isEqualTo("DH");
    assertThat(item.key).isEqualTo(WorkItemKey.of(REPO, SLICE, FP));
    assertThat(h.stores.links().find(REPO, SLICE, FP).orElseThrow().itemId()).isEqualTo(item.id);
  }

  @Test
  void aRegressedFindingWithNoItemGetsOne() {
    h.firstSeen(FP, "run-1");
    h.resolved(FP, "run-1");
    h.regressed(FP, "run-1");

    assertThat(reconcile()).isEqualTo(SyncResult.CREATED);
    assertThat(h.tracker.all()).hasSize(1);
  }

  @Test
  void aFindingSeenForTheFirstTimeGetsNoItemYet() {
    h.firstSeen(FP, "run-1");

    assertThat(reconcile()).isEqualTo(SyncResult.NO_ACTION);
    assertThat(h.tracker.all()).isEmpty();
    assertThat(h.stores.links().find(REPO, SLICE, FP)).isEmpty();
  }

  @Test
  void theItemNamesTheFindingButNeverAContributorOrSource() {
    open();
    reconcile();

    FakeTracker.Item item = h.tracker.all().get(0);
    String everything = item.content.title() + item.content.details() + item.content.runLink();
    assertThat(everything)
        .contains("codemaat.hotspot", "src/Foo.java", "HIGH", "https://cp.example/runs/run-1")
        .doesNotContain(WorkItemHarness.CONTRIBUTOR);
  }

  @Test
  void aFindingBelowTheSeverityThresholdGetsNoItem() {
    h.run("run-1", WorkItemHarness.finding(FP, Severity.MEDIUM));
    open();

    assertThat(reconcile()).isEqualTo(SyncResult.SKIPPED_BELOW_SEVERITY);
    assertThat(h.tracker.all()).isEmpty();
  }

  @Test
  void aCriticalFindingAndOneAtTheThresholdBothQualify() {
    WorkItemSettings settings = WorkItemSettings.defaults();
    assertThat(settings.qualifies(Severity.CRITICAL)).isTrue();
    assertThat(settings.qualifies(Severity.HIGH)).isTrue();
    assertThat(settings.qualifies(Severity.MEDIUM)).isFalse();
    assertThat(settings.qualifies(Severity.INFO)).isFalse();
  }

  // ---- CP-AC-64 ---------------------------------------------------------------------------

  @Test
  void askingTenTimesStillLeavesOneItem() {
    open();

    assertThat(reconcile()).isEqualTo(SyncResult.CREATED);
    for (int i = 0; i < 9; i++) {
      assertThat(reconcile()).isEqualTo(SyncResult.NO_ACTION);
    }

    assertThat(h.tracker.creates.get()).isEqualTo(1);
    assertThat(h.tracker.all()).hasSize(1);
  }

  // ---- CP-AC-65 ---------------------------------------------------------------------------

  @Test
  void aResolvedFindingClosesItsItem() {
    open();
    reconcile();
    h.resolved(FP, "run-2");

    assertThat(reconcile()).isEqualTo(SyncResult.CLOSED);

    FakeTracker.Item item = h.tracker.all().get(0);
    assertThat(item.status).isEqualTo(WorkItemStatus.CLOSED);
    assertThat(item.comments).anyMatch(c -> c.contains("no longer present"));
    assertThat(h.stores.links().find(REPO, SLICE, FP).orElseThrow().status())
        .isEqualTo(WorkItemStatus.CLOSED);
  }

  @Test
  void aResolvedFindingWithNoItemChangesNothing() {
    h.firstSeen(FP, "run-1");
    h.resolved(FP, "run-1");

    assertThat(reconcile()).isEqualTo(SyncResult.NO_ACTION);
    assertThat(h.tracker.calls).isEmpty();
  }

  // ---- CP-AC-66 ---------------------------------------------------------------------------

  @Test
  void aRegressionReopensTheSameItemAndCreatesNoSecond() {
    open();
    reconcile();
    h.resolved(FP, "run-2");
    reconcile();
    h.regressed(FP, "run-3");

    assertThat(reconcile()).isEqualTo(SyncResult.REOPENED);

    assertThat(h.tracker.all()).hasSize(1);
    FakeTracker.Item item = h.tracker.all().get(0);
    assertThat(item.status).isEqualTo(WorkItemStatus.OPEN);
    assertThat(item.history)
        .containsExactly(WorkItemStatus.OPEN, WorkItemStatus.CLOSED, WorkItemStatus.OPEN);
    assertThat(item.comments).anyMatch(c -> c.contains("reappeared"));
  }

  @Test
  void aReopenedItemIsNotGatedByStageOrSeverity() {
    open();
    reconcile();
    h.resolved(FP, "run-2");
    reconcile();
    h.regressed(FP, "run-3");
    h.stages.put(REPO, RolloutStage.STAGE_2);
    h.run("run-1", WorkItemHarness.finding(FP, Severity.LOW));

    assertThat(reconcile()).isEqualTo(SyncResult.REOPENED);
  }

  @Test
  void anExistingItemKeepsBeingClosedEvenAfterTheRepositoryMovesBackAStage() {
    open();
    reconcile();
    h.stages.put(REPO, RolloutStage.STAGE_2);
    h.resolved(FP, "run-2");

    assertThat(reconcile()).isEqualTo(SyncResult.CLOSED);
  }

  // ---- suppression ------------------------------------------------------------------------

  @Test
  void aSuppressedFindingPutsItsItemOnHoldWithTheDetailsInAComment() {
    open();
    reconcile();
    h.suppression(FP, "vendor fix lands in Q3", Duration.ofDays(30));
    h.suppressed(FP, "run-2");

    assertThat(reconcile()).isEqualTo(SyncResult.PUT_ON_HOLD);

    FakeTracker.Item item = h.tracker.all().get(0);
    assertThat(item.status).isEqualTo(WorkItemStatus.ON_HOLD);
    assertThat(item.comments)
        .anyMatch(
            c ->
                c.contains("vendor fix lands in Q3")
                    && c.contains(h.clock.instant().plus(Duration.ofDays(30)).toString()));
  }

  @Test
  void whenTheSuppressionExpiresTheItemIsReopened() {
    open();
    reconcile();
    h.suppression(FP, "later", Duration.ofDays(1));
    h.suppressed(FP, "run-2");
    reconcile();
    h.suppressionExpired(FP, "run-3");

    assertThat(reconcile()).isEqualTo(SyncResult.REOPENED);

    FakeTracker.Item item = h.tracker.all().get(0);
    assertThat(item.status).isEqualTo(WorkItemStatus.OPEN);
    assertThat(item.comments).anyMatch(c -> c.contains("suppression has expired"));
    assertThat(h.tracker.all()).hasSize(1);
  }

  @Test
  void aSuppressionWhoseDetailsCannotBeFoundStillPutsTheItemOnHold() {
    open();
    reconcile();
    h.suppressed(FP, "run-2");

    assertThat(reconcile()).isEqualTo(SyncResult.PUT_ON_HOLD);
    assertThat(h.tracker.all().get(0).comments).anyMatch(c -> c.contains("suppressed"));
  }

  @Test
  void aSuppressedFindingWithNoItemChangesNothing() {
    h.firstSeen(FP, "run-1");
    h.transition(
        FP,
        com.debthunter.controlplane.store.lifecycle.FindingState.NEW,
        com.debthunter.controlplane.store.lifecycle.FindingState.SUPPRESSED,
        com.debthunter.controlplane.store.lifecycle.TransitionCause.SUPPRESSION_APPLIED,
        "run-1");

    assertThat(reconcile()).isEqualTo(SyncResult.NO_ACTION);
    assertThat(h.tracker.calls).isEmpty();
  }

  @Test
  void aSuppressionReasonCannotSmuggleControlCharactersOrMentionsIntoAComment() {
    open();
    reconcile();
    h.suppression(FP, "ping @everyone\nnow\u0007", Duration.ofDays(3));
    h.suppressed(FP, "run-2");
    reconcile();

    String comment =
        h.tracker.all().get(0).comments.get(h.tracker.all().get(0).comments.size() - 1);
    assertThat(comment).doesNotContain("\u0007").doesNotContain("@everyone").doesNotContain("\n");
  }

  // ---- CP-AC-67: recovery -----------------------------------------------------------------

  @Test
  void anItemCreatedByAnAttemptThatDiedBeforeRecordingItIsAdoptedNotDuplicated() {
    open();
    h.tracker.failAfterNextCreate = true;

    assertThatThrownBy(this::reconcile).isInstanceOf(IllegalStateException.class);
    assertThat(h.tracker.all()).hasSize(1);
    assertThat(h.stores.links().find(REPO, SLICE, FP).orElseThrow().hasItem()).isFalse();

    assertThat(reconcile()).isEqualTo(SyncResult.ADOPTED);

    assertThat(h.tracker.creates.get()).isEqualTo(1);
    assertThat(h.stores.links().find(REPO, SLICE, FP).orElseThrow().itemId())
        .isEqualTo(h.tracker.all().get(0).id);
  }

  @Test
  void anAdoptedItemIsMovedIfTheFindingHasMovedOnSince() {
    open();
    h.tracker.failAfterNextCreate = true;
    assertThatThrownBy(this::reconcile).isInstanceOf(IllegalStateException.class);
    h.resolved(FP, "run-2");

    assertThat(reconcile()).isEqualTo(SyncResult.CLOSED);

    assertThat(h.tracker.all()).hasSize(1);
    assertThat(h.tracker.all().get(0).status).isEqualTo(WorkItemStatus.CLOSED);
  }

  @Test
  void aTrackerThatIsDownHoldsTheWorkAndLeavesNoHalfFinishedItem() {
    open();
    h.tracker.down = true;

    assertThatThrownBy(this::reconcile).isInstanceOf(DeliveryUnavailableException.class);
    assertThat(h.tracker.all()).isEmpty();

    h.tracker.down = false;
    assertThat(reconcile()).isEqualTo(SyncResult.CREATED);
    assertThat(h.tracker.creates.get()).isEqualTo(1);
  }

  @Test
  void aTrackerThatRefusesIsNotReportedAsUnavailable() {
    open();
    h.tracker.refuseCreate = true;

    assertThatThrownBy(this::reconcile)
        .isInstanceOf(TrackerRejectedException.class)
        .isNotInstanceOf(DeliveryUnavailableException.class);
  }

  @Test
  void anotherWorkerHoldingTheFindingMakesThisOneWaitNotWriteTwice() {
    open();
    reconcile();
    h.resolved(FP, "run-2");
    h.stores
        .links()
        .claim(
            REPO, SLICE, FP, "other-worker", h.clock.instant(), h.clock.instant().plusSeconds(60));

    assertThatThrownBy(this::reconcile)
        .isInstanceOf(DeliveryUnavailableException.class)
        .hasMessageContaining("another worker");
    assertThat(h.tracker.moves.get()).isZero();

    h.clock.advance(Duration.ofSeconds(61));
    assertThat(reconcile()).isEqualTo(SyncResult.CLOSED);
  }

  @Test
  void anItemAlreadyInLineNeedsNeitherTheTrackerNorTheClaim() {
    open();
    reconcile();
    h.tracker.calls.clear();
    h.stores
        .links()
        .claim(
            REPO, SLICE, FP, "other-worker", h.clock.instant(), h.clock.instant().plusSeconds(60));

    assertThat(reconcile()).isEqualTo(SyncResult.NO_ACTION);
    assertThat(h.tracker.calls).isEmpty();
  }

  @Test
  void theClaimIsReleasedEvenWhenTheTrackerFails() {
    open();
    h.tracker.down = true;
    assertThatThrownBy(this::reconcile).isInstanceOf(DeliveryUnavailableException.class);
    h.tracker.down = false;

    // A second attempt straight away is not blocked by a claim the first left behind.
    assertThat(reconcile()).isEqualTo(SyncResult.CREATED);
  }

  // ---- CP-AC-69 ---------------------------------------------------------------------------

  @Test
  void atStageTwoNoItemIsCreated() {
    h.stages.put(REPO, RolloutStage.STAGE_2);
    open();

    assertThat(reconcile()).isEqualTo(SyncResult.SKIPPED_BELOW_STAGE);
    assertThat(h.tracker.all()).isEmpty();
    assertThat(h.stores.links().find(REPO, SLICE, FP)).isEmpty();
  }

  @Test
  void aRepositoryNotInTheRolloutGetsNoItems() {
    h.stages.remove(REPO);
    open();

    assertThat(reconcile()).isEqualTo(SyncResult.SKIPPED_BELOW_STAGE);
  }

  @Test
  void stageFourCreatesItemsToo() {
    h.stages.put(REPO, RolloutStage.STAGE_4);
    open();

    assertThat(reconcile()).isEqualTo(SyncResult.CREATED);
  }

  // ---- what can never be done -------------------------------------------------------------

  @Test
  void aFindingWithNoHistoryCannotBeActedOn() {
    assertThatThrownBy(this::reconcile).isInstanceOf(UndeliverableException.class);
  }

  @Test
  void aFindingMissingFromTheRunThatReportedItCannotHaveAnItemCreated() {
    h.run("run-1");
    open();

    assertThatThrownBy(this::reconcile).isInstanceOf(UndeliverableException.class);
    assertThat(h.tracker.all()).isEmpty();
  }

  @Test
  void aNewlySettingChangeToTheThresholdIsHonoured() {
    WorkItemSettings strict = new WorkItemSettings(Severity.CRITICAL, Duration.ofMinutes(1));
    assertThat(strict.qualifies(Severity.HIGH)).isFalse();
    assertThatThrownBy(() -> new WorkItemSettings(Severity.HIGH, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
