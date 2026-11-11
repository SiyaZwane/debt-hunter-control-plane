package com.debthunter.controlplane.governance.rollout;

import static com.debthunter.controlplane.governance.rollout.StageFixture.REPO;
import static com.debthunter.controlplane.governance.rollout.StageFixture.day;
import static com.debthunter.controlplane.governance.rollout.StageFixture.stage1Evidence;
import static com.debthunter.controlplane.governance.rollout.StageFixture.stage2Evidence;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.governance.rollout.StageOutcome.Changed;
import com.debthunter.controlplane.governance.rollout.StageOutcome.Reason;
import com.debthunter.controlplane.governance.rollout.StageOutcome.Refused;
import com.debthunter.controlplane.store.jdbc.StoreException;
import com.debthunter.controlplane.store.rollout.InMemoryStageRepository;
import com.debthunter.controlplane.store.rollout.RolloutStage;
import com.debthunter.controlplane.store.rollout.StageConflictException;
import com.debthunter.controlplane.store.rollout.StageEvent;
import com.debthunter.controlplane.store.rollout.StageRecord;
import com.debthunter.controlplane.store.rollout.StageRepository;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class StageManagerTest {

  private final StageFixture f = new StageFixture();

  private static Refused refused(StageOutcome outcome) {
    assertThat(outcome).isInstanceOf(Refused.class);
    return (Refused) outcome;
  }

  // ---- CP-AC-48: stage 1 creates no work items ----------------------------------------------

  @Test
  void aRepositoryNotInTheRolloutHasWorkItemCreationDisabled() {
    assertThat(f.manager.isWorkItemCreationEnabled("never/heard")).isFalse();
  }

  @Test
  void aRepositoryEnrolledAtStageOneHasWorkItemCreationDisabled() {
    f.manager.enrol(REPO, "team-widgets", "lead", StageFixture.T0);

    assertThat(f.manager.stageOf(REPO))
        .get()
        .extracting(StageRecord::stage)
        .isEqualTo(RolloutStage.STAGE_1);
    assertThat(f.manager.isWorkItemCreationEnabled(REPO)).isFalse();
  }

  @Test
  void enrollingTwiceIsRefusedAndKeepsTheFirstRecord() {
    f.manager.enrol(REPO, "team-widgets", "lead", StageFixture.T0);

    assertThat(refused(f.manager.enrol(REPO, "someone-else", "lead", day(5))).reason())
        .isEqualTo(Reason.ALREADY_ENROLLED);
    assertThat(f.manager.stageOf(REPO).orElseThrow().owner()).isEqualTo("team-widgets");
  }

  @Test
  void aRepositoryWithoutANamedOwnerCannotBeEnrolled() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> f.manager.enrol(REPO, " ", "lead", StageFixture.T0));
  }

  // ---- CP-AC-49: stage 3 opens the gate ----------------------------------------------------

  @Test
  void theGateOpensExactlyAtStageThree() {
    f.manager.enrol(REPO, "team-widgets", "lead", StageFixture.T0);
    assertThat(f.manager.isWorkItemCreationEnabled(REPO)).isFalse();

    f.manager.advance(REPO, "lead", day(1), stage1Evidence());
    assertThat(f.manager.isWorkItemCreationEnabled(REPO)).as("stage 2").isFalse();

    f.manager.advance(REPO, "lead", day(2), stage2Evidence());
    assertThat(f.manager.isWorkItemCreationEnabled(REPO)).as("stage 3").isTrue();

    f.manager.advance(REPO, "lead", day(3), Map.of());
    assertThat(f.manager.isWorkItemCreationEnabled(REPO)).as("stage 4").isTrue();
  }

  @Test
  void movingBackBelowStageThreeClosesTheGate() {
    f.reach(RolloutStage.STAGE_3);

    f.manager.regress(REPO, RolloutStage.STAGE_2, "lead", "too noisy", day(10));

    assertThat(f.manager.isWorkItemCreationEnabled(REPO)).isFalse();
  }

  @Test
  void anUnreadableStoreIsNeverAnswerOpen() {
    StageRepository broken =
        new StageRepositoryDelegate(new InMemoryStageRepository()) {
          @Override
          public Optional<StageRecord> find(String repository) {
            throw new StoreException("database down", null);
          }
        };
    StageManager manager = new StageManager(broken, StageFixture.POLICY, e -> {});

    assertThatThrownBy(() -> manager.isWorkItemCreationEnabled(REPO))
        .isInstanceOf(StoreException.class);
  }

  // ---- advancing needs evidence -------------------------------------------------------------

  @Test
  void advancingNeedsEveryExitCriterionAndSaysWhichAreMissing() {
    f.manager.enrol(REPO, "team-widgets", "lead", StageFixture.T0);

    Refused refused =
        refused(f.manager.advance(REPO, "lead", day(1), Map.of("baseline-established", "link")));

    assertThat(refused.reason()).isEqualTo(Reason.MISSING_CRITERIA);
    assertThat(refused.missingCriteria()).containsExactly("owner-signoff");
    assertThat(f.manager.stageOf(REPO).orElseThrow().stage()).isEqualTo(RolloutStage.STAGE_1);
    assertThat(f.manager.history(REPO)).hasSize(1);
  }

  @Test
  void aCriterionWithBlankEvidenceDoesNotCount() {
    f.manager.enrol(REPO, "team-widgets", "lead", StageFixture.T0);

    Refused refused =
        refused(
            f.manager.advance(
                REPO,
                "lead",
                day(1),
                Map.of("baseline-established", "link", "owner-signoff", "  ")));

    assertThat(refused.missingCriteria()).containsExactly("owner-signoff");
  }

  @Test
  void noEvidenceAtAllIsRefusedNotAnError() {
    f.manager.enrol(REPO, "team-widgets", "lead", StageFixture.T0);

    assertThat(refused(f.manager.advance(REPO, "lead", day(1), null)).missingCriteria())
        .containsExactlyInAnyOrder("baseline-established", "owner-signoff");
  }

  @Test
  void onlyTheEvidenceForRequiredCriteriaIsRecorded() {
    f.manager.enrol(REPO, "team-widgets", "lead", StageFixture.T0);
    Map<String, String> padded = new java.util.HashMap<>(stage1Evidence());
    padded.put("unrelated", "noise");

    Changed changed = (Changed) f.manager.advance(REPO, "lead", day(1), padded);

    assertThat(changed.record().exitEvidence())
        .containsOnlyKeys("baseline-established", "owner-signoff");
  }

  @Test
  void aStageIsNeverSkipped() {
    f.manager.enrol(REPO, "team-widgets", "lead", StageFixture.T0);

    f.manager.advance(REPO, "lead", day(1), stage1Evidence());

    assertThat(f.manager.stageOf(REPO).orElseThrow().stage()).isEqualTo(RolloutStage.STAGE_2);
  }

  @Test
  void theLastStageHasNowhereToAdvanceTo() {
    f.reach(RolloutStage.STAGE_4);

    assertThat(refused(f.manager.advance(REPO, "lead", day(20), Map.of())).reason())
        .isEqualTo(Reason.NO_NEXT_STAGE);
  }

  @Test
  void aRepositoryNotInTheRolloutCannotBeMoved() {
    assertThat(refused(f.manager.advance("ghost", "lead", day(1), stage1Evidence())).reason())
        .isEqualTo(Reason.NOT_ENROLLED);
    assertThat(
            refused(f.manager.regress("ghost", RolloutStage.STAGE_1, "lead", "why", day(1)))
                .reason())
        .isEqualTo(Reason.NOT_ENROLLED);
    assertThat(refused(f.manager.grantExemption("ghost", "why", "cto", day(1), day(9))).reason())
        .isEqualTo(Reason.NOT_ENROLLED);
  }

  @Test
  void aChangeDatedBeforeTheCurrentStageBeganIsRefused() {
    f.reach(RolloutStage.STAGE_2);

    assertThat(refused(f.manager.advance(REPO, "lead", day(0), stage2Evidence())).reason())
        .isEqualTo(Reason.BEFORE_CURRENT_STAGE);
    assertThat(
            refused(f.manager.regress(REPO, RolloutStage.STAGE_1, "lead", "why", day(0))).reason())
        .isEqualTo(Reason.BEFORE_CURRENT_STAGE);
  }

  // ---- regression ---------------------------------------------------------------------------

  @Test
  void movingBackNeedsAReasonAndAnEarlierStage() {
    f.reach(RolloutStage.STAGE_2);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> f.manager.regress(REPO, RolloutStage.STAGE_1, "lead", " ", day(10)));
    assertThat(
            refused(f.manager.regress(REPO, RolloutStage.STAGE_2, "lead", "why", day(10))).reason())
        .isEqualTo(Reason.NOT_EARLIER);
    assertThat(
            refused(f.manager.regress(REPO, RolloutStage.STAGE_3, "lead", "why", day(10))).reason())
        .isEqualTo(Reason.NOT_EARLIER);
  }

  @Test
  void movingBackStartsANewStayWithNoCarriedEvidence() {
    f.reach(RolloutStage.STAGE_3);

    Changed changed =
        (Changed) f.manager.regress(REPO, RolloutStage.STAGE_1, "lead", "regrouping", day(30));

    assertThat(changed.record().stage()).isEqualTo(RolloutStage.STAGE_1);
    assertThat(changed.record().effectiveFrom()).isEqualTo(day(30));
    assertThat(changed.record().exitEvidence()).isEmpty();
    assertThat(changed.record().owner()).isEqualTo("team-widgets");
  }

  // ---- CP-AC-50: transitions recorded ------------------------------------------------------

  @Test
  void everyChangeIsRecordedWithApproverDateAndSatisfiedCriteria() {
    f.manager.enrol(REPO, "team-widgets", "enroller", day(0));
    f.manager.advance(REPO, "approver-1", day(2), stage1Evidence());
    f.manager.regress(REPO, RolloutStage.STAGE_1, "approver-2", "regrouping", day(9));

    List<StageEvent> history = f.manager.history(REPO);

    assertThat(history)
        .extracting(StageEvent::kind)
        .containsExactly(
            StageEvent.Kind.ENROLLED, StageEvent.Kind.ADVANCED, StageEvent.Kind.REGRESSED);
    StageEvent advanced = history.get(1);
    assertThat(advanced.actor()).isEqualTo("approver-1");
    assertThat(advanced.at()).isEqualTo(day(2));
    assertThat(advanced.fromStage()).isEqualTo(RolloutStage.STAGE_1);
    assertThat(advanced.toStage()).isEqualTo(RolloutStage.STAGE_2);
    assertThat(advanced.criteria()).isEqualTo(stage1Evidence());
    assertThat(history.get(2).reason()).isEqualTo("regrouping");
    assertThat(history.get(0).actor()).isEqualTo("enroller");
  }

  @Test
  void aRefusedChangeLeavesNoEvent() {
    f.manager.enrol(REPO, "team-widgets", "lead", day(0));

    f.manager.advance(REPO, "lead", day(1), Map.of());

    assertThat(f.manager.history(REPO)).hasSize(1);
  }

  @Test
  void aChangeThatLostARaceIsReportedAsAConflictAndWritesNothing() {
    f.manager.enrol(REPO, "team-widgets", "lead", day(0));
    InMemoryStageRepository real = (InMemoryStageRepository) f.repository;
    StageRepository view =
        new StageRepositoryDelegate(real) {
          @Override
          public void apply(StageRecord updated, StageEvent event) {
            throw new StageConflictException("someone else moved it");
          }
        };
    StageManager manager = new StageManager(view, StageFixture.POLICY, e -> {});

    Refused refused = refused(manager.advance(REPO, "lead", day(1), stage1Evidence()));

    assertThat(refused.reason()).isEqualTo(Reason.CONFLICT);
    assertThat(real.events(REPO)).hasSize(1);
  }

  // ---- exemptions ---------------------------------------------------------------------------

  @Test
  void anExemptionIsRecordedOnTheRecordAndInTheLog() {
    f.manager.enrol(REPO, "team-widgets", "lead", day(0));

    f.manager.grantExemption(REPO, "migration in flight", "cto", day(10), day(40));

    StageRecord record = f.manager.stageOf(REPO).orElseThrow();
    assertThat(record.exemption().approvedBy()).isEqualTo("cto");
    assertThat(record.exemption().expiresAt()).isEqualTo(day(40));
    assertThat(f.manager.history(REPO).get(1).kind()).isEqualTo(StageEvent.Kind.EXEMPTION_GRANTED);
  }

  @Test
  void anExemptionMustExpireAfterItIsGranted() {
    f.manager.enrol(REPO, "team-widgets", "lead", day(0));

    assertThatIllegalArgumentException()
        .isThrownBy(() -> f.manager.grantExemption(REPO, "why", "cto", day(10), day(10)));
  }

  @Test
  void movingToAnotherStageEndsTheExemptionAndTheEscalationMarker() {
    f.manager.enrol(REPO, "team-widgets", "lead", day(0));
    f.manager.grantExemption(REPO, "why", "cto", day(1), day(100));

    f.manager.advance(REPO, "lead", day(5), stage1Evidence());

    StageRecord record = f.manager.stageOf(REPO).orElseThrow();
    assertThat(record.exemption()).isNull();
    assertThat(record.escalatedAt()).isNull();
  }

  // ---- CP-AC-51: maximum hold escalation ---------------------------------------------------

  @Test
  void aRepositoryWithinItsMaximumHoldIsNotEscalated() {
    f.manager.enrol(REPO, "team-widgets", "lead", day(0));

    EscalationSweepResult result = f.manager.escalateOverdue(day(30));

    assertThat(result.escalated()).isEmpty();
    assertThat(f.delivered).isEmpty();
  }

  @Test
  void aRepositoryPastItsMaximumHoldIsEscalatedWithEverythingTheRecipientNeeds() {
    f.manager.enrol(REPO, "team-widgets", "lead", day(0));

    EscalationSweepResult result = f.manager.escalateOverdue(day(31));

    assertThat(result.escalated()).hasSize(1);
    StageEscalation escalation = f.delivered.get(0);
    assertThat(escalation.repository()).isEqualTo(REPO);
    assertThat(escalation.stage()).isEqualTo(RolloutStage.STAGE_1);
    assertThat(escalation.owner()).isEqualTo("team-widgets");
    assertThat(escalation.heldSince()).isEqualTo(day(0));
    assertThat(escalation.heldFor()).isEqualTo(Duration.ofDays(31));
    assertThat(escalation.maxHold()).isEqualTo(Duration.ofDays(30));
  }

  @Test
  void aStayIsEscalatedOnceNotOnEverySweep() {
    f.manager.enrol(REPO, "team-widgets", "lead", day(0));

    f.manager.escalateOverdue(day(31));
    f.manager.escalateOverdue(day(32));
    f.manager.escalateOverdue(day(90));

    assertThat(f.delivered).hasSize(1);
  }

  @Test
  void aNewStayCanBeEscalatedAgain() {
    f.manager.enrol(REPO, "team-widgets", "lead", day(0));
    f.manager.escalateOverdue(day(31));
    f.manager.advance(REPO, "lead", day(32), stage1Evidence());

    f.manager.escalateOverdue(day(32 + 61));

    assertThat(f.delivered)
        .extracting(StageEscalation::stage)
        .containsExactly(RolloutStage.STAGE_1, RolloutStage.STAGE_2);
  }

  @Test
  void anActiveExemptionExcusesTheHoldAndItsExpiryMakesTheRepositoryDueAgain() {
    f.manager.enrol(REPO, "team-widgets", "lead", day(0));
    f.manager.grantExemption(REPO, "migration", "cto", day(20), day(50));

    f.manager.escalateOverdue(day(40));
    assertThat(f.delivered).as("excused").isEmpty();

    f.manager.escalateOverdue(day(51));
    assertThat(f.delivered).as("exemption expired").hasSize(1);
  }

  @Test
  void aStageWithNoMaximumHoldIsNeverEscalated() {
    f.reach(RolloutStage.STAGE_4);

    f.manager.escalateOverdue(day(10_000));

    assertThat(f.delivered).isEmpty();
  }

  @Test
  void aSinkThatIsDownLeavesTheRepositoryDueAndItIsDeliveredNextSweep() {
    f.manager.enrol(REPO, "team-widgets", "lead", day(0));
    f.sinkDown = true;

    EscalationSweepResult first = f.manager.escalateOverdue(day(31));

    assertThat(first.escalated()).isEmpty();
    assertThat(first.failed()).containsExactly(REPO);
    assertThat(f.manager.stageOf(REPO).orElseThrow().escalatedAt()).isNull();

    f.sinkDown = false;
    assertThat(f.manager.escalateOverdue(day(32)).escalated()).hasSize(1);
  }

  @Test
  void oneRepositoryFailingDoesNotStopTheOthers() {
    f.manager.enrol("acme/a", "team", "lead", day(0));
    f.manager.enrol("acme/b", "team", "lead", day(0));
    StageManager picky =
        new StageManager(
            f.repository,
            StageFixture.POLICY,
            e -> {
              if (e.repository().equals("acme/a")) {
                throw new IllegalStateException("cannot deliver a");
              }
              f.delivered.add(e);
            });

    EscalationSweepResult result = picky.escalateOverdue(day(40));

    assertThat(result.failed()).containsExactly("acme/a");
    assertThat(result.escalated())
        .extracting(StageEscalation::repository)
        .containsExactly("acme/b");
  }

  @Test
  void aDeliveryThatCouldNotBeRecordedIsReportedAndWillBeRepeated() {
    f.manager.enrol(REPO, "team-widgets", "lead", day(0));
    StageRepository cannotRecord =
        new StageRepositoryDelegate((InMemoryStageRepository) f.repository) {
          @Override
          public void update(StageRecord updated) {
            throw new StoreException("database down", null);
          }
        };
    StageManager manager = new StageManager(cannotRecord, StageFixture.POLICY, f.delivered::add);

    EscalationSweepResult result = manager.escalateOverdue(day(31));

    assertThat(result.escalated()).isEmpty();
    assertThat(result.failed()).containsExactly(REPO);
    assertThat(f.delivered).hasSize(1);
  }

  // ---- the rules of the rollout ------------------------------------------------------------

  @Test
  void aPolicyMustSayWhatLeavingEveryNonFinalStageRequires() {
    assertThatIllegalStateException()
        .isThrownBy(
            () ->
                StagePolicy.builder()
                    .exitCriteria(RolloutStage.STAGE_1, "a")
                    .exitCriteria(RolloutStage.STAGE_2, "b")
                    .build())
        .withMessageContaining("STAGE_3");
  }

  @Test
  void aPolicyRefusesBlankCriteriaAndNonPositiveHolds() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> StagePolicy.builder().exitCriteria(RolloutStage.STAGE_1, " "));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> StagePolicy.builder().maxHold(RolloutStage.STAGE_1, Duration.ZERO));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> StagePolicy.builder().maxHold(RolloutStage.STAGE_1, Duration.ofDays(-1)));
  }

  @Test
  void theFinalStageNeedsNoExitCriteria() {
    StagePolicy policy =
        StagePolicy.builder()
            .exitCriteria(RolloutStage.STAGE_1)
            .exitCriteria(RolloutStage.STAGE_2)
            .exitCriteria(RolloutStage.STAGE_3)
            .build();

    assertThat(policy.exitCriteria(RolloutStage.STAGE_4)).isEmpty();
    assertThat(policy.maxHold(RolloutStage.STAGE_1)).isNull();
  }

  /** Forwards everything, so a test can override one method. */
  private static class StageRepositoryDelegate implements StageRepository {
    private final InMemoryStageRepository delegate;

    StageRepositoryDelegate(InMemoryStageRepository delegate) {
      this.delegate = delegate;
    }

    @Override
    public Optional<StageRecord> find(String repository) {
      return delegate.find(repository);
    }

    @Override
    public List<StageRecord> all() {
      return delegate.all();
    }

    @Override
    public Map<String, RolloutStage> currentStages() {
      return delegate.currentStages();
    }

    @Override
    public void apply(StageRecord updated, StageEvent event) {
      delegate.apply(updated, event);
    }

    @Override
    public void update(StageRecord updated) {
      delegate.update(updated);
    }

    @Override
    public List<StageEvent> events(String repository) {
      return delegate.events(repository);
    }
  }
}
