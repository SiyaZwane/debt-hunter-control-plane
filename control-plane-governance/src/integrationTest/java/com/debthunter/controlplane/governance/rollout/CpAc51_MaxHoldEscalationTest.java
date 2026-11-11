package com.debthunter.controlplane.governance.rollout;

import static com.debthunter.controlplane.governance.rollout.StageFixture.REPO;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.rollout.RolloutStage;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-51: a repository that stays at a stage past the maximum hold is escalated, once, to its
 * owner; an approved exemption excuses it until the exemption expires.
 */
@Tag("integration")
class CpAc51_MaxHoldEscalationTest {

  private final JdbcStageFixture db = new JdbcStageFixture();

  @Test
  void aRepositoryPastItsMaximumHoldIsEscalatedOnceAcrossSweepsAndRestarts() {
    db.stages.manager.enrol(REPO, "team-widgets", "lead", StageFixture.day(0));

    db.stages.manager.escalateOverdue(StageFixture.day(29));
    db.stages.manager.escalateOverdue(StageFixture.day(31));
    // A restarted control plane must not repeat it: the marker is in the database.
    StageManager restarted =
        new StageManager(
            new com.debthunter.controlplane.store.rollout.JdbcStageRepository(db.dataSource),
            StageFixture.POLICY,
            db.stages.delivered::add);
    restarted.escalateOverdue(StageFixture.day(45));

    assertThat(db.stages.delivered).hasSize(1);
    assertThat(db.stages.delivered.get(0).owner()).isEqualTo("team-widgets");
    assertThat(db.stages.delivered.get(0).stage()).isEqualTo(RolloutStage.STAGE_1);
  }

  @Test
  void anExemptionHoldsEscalationBackUntilItExpires() {
    db.stages.manager.enrol(REPO, "team-widgets", "lead", StageFixture.day(0));
    db.stages.manager.grantExemption(
        REPO, "migration", "cto", StageFixture.day(20), StageFixture.day(50));

    db.stages.manager.escalateOverdue(StageFixture.day(40));
    assertThat(db.stages.delivered).isEmpty();

    db.stages.manager.escalateOverdue(StageFixture.day(51));
    assertThat(db.stages.delivered).hasSize(1);
  }

  @Test
  void aDownSinkLeavesTheEscalationPendingInThePersistedRecord() {
    db.stages.manager.enrol(REPO, "team-widgets", "lead", StageFixture.day(0));
    db.stages.sinkDown = true;

    db.stages.manager.escalateOverdue(StageFixture.day(31));

    assertThat(db.anotherInstance().stageOf(REPO).orElseThrow().escalatedAt()).isNull();
    db.stages.sinkDown = false;
    db.stages.manager.escalateOverdue(StageFixture.day(32));
    assertThat(db.stages.delivered).hasSize(1);
    assertThat(db.anotherInstance().stageOf(REPO).orElseThrow().escalatedAt()).isNotNull();
  }

  @Test
  void escalationNeverTouchesTheStageItself() {
    db.stages.manager.enrol(REPO, "team-widgets", "lead", StageFixture.day(0));

    db.stages.manager.escalateOverdue(StageFixture.day(100));

    assertThat(db.anotherInstance().stageOf(REPO).orElseThrow().stage())
        .isEqualTo(RolloutStage.STAGE_1);
    assertThat(db.anotherInstance().history(REPO)).hasSize(1);
  }
}
