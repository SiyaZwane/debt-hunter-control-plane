package com.debthunter.controlplane.governance.rollout;

import static com.debthunter.controlplane.governance.rollout.StageFixture.REPO;
import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CP-AC-48: a repository at stage 1 has work-item creation disabled, durably. */
@Tag("integration")
class CpAc48_Stage1NoWorkItemTest {

  private final JdbcStageFixture db = new JdbcStageFixture();

  @Test
  void aStageOneRepositoryHasWorkItemCreationDisabled() {
    db.stages.manager.enrol(REPO, "team-widgets", "lead", StageFixture.T0);

    assertThat(db.stages.manager.isWorkItemCreationEnabled(REPO)).isFalse();
    assertThat(db.anotherInstance().isWorkItemCreationEnabled(REPO)).isFalse();
  }

  @Test
  void aRepositoryNobodyEnrolledAlsoHasItDisabled() {
    assertThat(db.stages.manager.isWorkItemCreationEnabled("never/enrolled")).isFalse();
  }

  @Test
  void aFailedAttemptToLeaveStageOneDoesNotOpenTheGate() {
    db.stages.manager.enrol(REPO, "team-widgets", "lead", StageFixture.T0);

    db.stages.manager.advance(REPO, "lead", StageFixture.day(1), java.util.Map.of());

    assertThat(db.anotherInstance().isWorkItemCreationEnabled(REPO)).isFalse();
  }
}
