package com.debthunter.controlplane.governance.rollout;

import static com.debthunter.controlplane.governance.rollout.StageFixture.REPO;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.rollout.RolloutStage;
import com.debthunter.controlplane.store.rollout.StageEvent;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-50: a stage transition is recorded with its approving authority, its date and the exit
 * criteria satisfied, and the record outlives the process that wrote it.
 */
@Tag("integration")
class CpAc50_TransitionEventRecordedTest {

  private final JdbcStageFixture db = new JdbcStageFixture();

  @Test
  void theEventSurvivesInPostgresWithApproverDateAndCriteria() {
    db.stages.manager.enrol(REPO, "team-widgets", "enroller", StageFixture.day(0));
    db.stages.manager.advance(
        REPO, "approver-1", StageFixture.day(3), StageFixture.stage1Evidence());

    List<StageEvent> history = db.anotherInstance().history(REPO);

    assertThat(history).hasSize(2);
    StageEvent advanced = history.get(1);
    assertThat(advanced.kind()).isEqualTo(StageEvent.Kind.ADVANCED);
    assertThat(advanced.fromStage()).isEqualTo(RolloutStage.STAGE_1);
    assertThat(advanced.toStage()).isEqualTo(RolloutStage.STAGE_2);
    assertThat(advanced.actor()).isEqualTo("approver-1");
    assertThat(advanced.at()).isEqualTo(StageFixture.day(3));
    assertThat(advanced.criteria()).isEqualTo(StageFixture.stage1Evidence());
  }

  @Test
  void theCurrentRecordCarriesTheEvidenceThatGotItThere() {
    db.stages.reach(RolloutStage.STAGE_2);

    assertThat(db.anotherInstance().stageOf(REPO).orElseThrow().exitEvidence())
        .isEqualTo(StageFixture.stage1Evidence());
  }

  @Test
  void aRefusedTransitionIsNotRecordedAndTheOldRecordStands() {
    db.stages.manager.enrol(REPO, "team-widgets", "lead", StageFixture.day(0));

    db.stages.manager.advance(REPO, "lead", StageFixture.day(1), java.util.Map.of());

    assertThat(db.anotherInstance().history(REPO)).hasSize(1);
    assertThat(db.anotherInstance().stageOf(REPO).orElseThrow().stage())
        .isEqualTo(RolloutStage.STAGE_1);
  }

  @Test
  void twoApproversAdvancingTheSameRepositoryFromTheSameStageCannotBothSucceed() throws Exception {
    db.stages.manager.enrol(REPO, "team-widgets", "lead", StageFixture.day(0));
    StageManager a = db.anotherInstance();
    StageManager b = db.anotherInstance();
    java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.ExecutorService pool =
        java.util.concurrent.Executors.newFixedThreadPool(2);
    try {
      var fa =
          pool.submit(
              () -> {
                go.await();
                return a.advance(
                    REPO, "approver-a", StageFixture.day(1), StageFixture.stage1Evidence());
              });
      var fb =
          pool.submit(
              () -> {
                go.await();
                return b.advance(
                    REPO, "approver-b", StageFixture.day(1), StageFixture.stage1Evidence());
              });
      go.countDown();
      List<StageOutcome> outcomes = List.of(fa.get(), fb.get());

      assertThat(outcomes).filteredOn(o -> o instanceof StageOutcome.Changed).hasSize(1);
      assertThat(db.anotherInstance().stageOf(REPO).orElseThrow().stage())
          .isEqualTo(RolloutStage.STAGE_2);
      assertThat(db.anotherInstance().history(REPO)).hasSize(2);
    } finally {
      pool.shutdownNow();
    }
  }
}
