package com.debthunter.controlplane.egress.workitem;

import static com.debthunter.controlplane.egress.workitem.WorkItemHarness.REPO;
import static com.debthunter.controlplane.egress.workitem.WorkItemHarness.SLICE;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.rollout.RolloutStage;
import com.debthunter.domain.Severity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CP-AC-69: below stage 3 no work item is created. */
@Tag("integration")
class CpAc69_Stage2NoWorkItemTest {

  @Test
  void aRepositoryAtStageTwoGetsNoWorkItems() {
    WorkItemHarness h = JdbcStores.harness();
    h.stages.put(REPO, RolloutStage.STAGE_2);
    h.run("run-1", WorkItemHarness.finding("fp-1", Severity.CRITICAL));
    h.firstSeen("fp-1", "run-1");
    h.opened("fp-1", "run-1");

    h.pump();

    assertThat(h.tracker.calls).isEmpty();
    assertThat(h.stores.links().find(REPO, SLICE, "fp-1")).isEmpty();
    assertThat(h.stores.queue().counts(WorkItemQueue.KIND).dead()).isZero();
    assertThat(h.alerts.alerts).isEmpty();
  }

  @Test
  void movingUpToStageThreeDoesNotBackfillButNewEventsGetItems() {
    WorkItemHarness h = JdbcStores.harness();
    h.stages.put(REPO, RolloutStage.STAGE_2);
    h.run("run-1", WorkItemHarness.finding("fp-1", Severity.HIGH));
    h.run("run-2", WorkItemHarness.finding("fp-2", Severity.HIGH));
    h.firstSeen("fp-1", "run-1");
    h.opened("fp-1", "run-1");
    h.pump();

    h.stages.put(REPO, RolloutStage.STAGE_3);
    h.pump();
    assertThat(h.tracker.all()).as("an already-open finding is not swept up").isEmpty();

    h.firstSeen("fp-2", "run-2");
    h.opened("fp-2", "run-2");
    h.pump();

    assertThat(h.tracker.all()).hasSize(1);
    assertThat(h.tracker.all().get(0).key).isEqualTo(WorkItemKey.of(REPO, SLICE, "fp-2"));
  }
}
