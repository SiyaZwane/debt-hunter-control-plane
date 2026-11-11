package com.debthunter.controlplane.governance.rollout;

import static com.debthunter.controlplane.governance.rollout.StageFixture.REPO;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.rollout.RolloutStage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-49: work-item creation is gated by stage and opens at stage 3. Creating the work item
 * itself is the egress step (CP-FR-13); this is the gate it will ask, answered from PostgreSQL.
 */
@Tag("integration")
class CpAc49_Stage3GatesAndCreatesWorkItemTest {

  private final JdbcStageFixture db = new JdbcStageFixture();

  @Test
  void theGateIsClosedAtStagesOneAndTwoAndOpenAtStageThree() {
    StageManager other = db.anotherInstance();
    db.stages.manager.enrol(REPO, "team-widgets", "lead", StageFixture.T0);
    assertThat(other.isWorkItemCreationEnabled(REPO)).as("stage 1").isFalse();

    db.stages.manager.advance(REPO, "lead", StageFixture.day(1), StageFixture.stage1Evidence());
    assertThat(other.isWorkItemCreationEnabled(REPO)).as("stage 2").isFalse();

    db.stages.manager.advance(REPO, "lead", StageFixture.day(2), StageFixture.stage2Evidence());
    assertThat(other.isWorkItemCreationEnabled(REPO)).as("stage 3").isTrue();
    assertThat(other.stageOf(REPO).orElseThrow().stage()).isEqualTo(RolloutStage.STAGE_3);
  }

  @Test
  void theGateIsPerRepository() {
    db.stages.reach(RolloutStage.STAGE_3);
    db.stages.manager.enrol("acme/other", "team-other", "lead", StageFixture.T0);

    assertThat(db.stages.manager.isWorkItemCreationEnabled(REPO)).isTrue();
    assertThat(db.stages.manager.isWorkItemCreationEnabled("acme/other")).isFalse();
  }

  @Test
  void movingBackClosesItAgain() {
    db.stages.reach(RolloutStage.STAGE_3);

    db.stages.manager.regress(REPO, RolloutStage.STAGE_2, "lead", "noisy", StageFixture.day(20));

    assertThat(db.anotherInstance().isWorkItemCreationEnabled(REPO)).isFalse();
  }
}
