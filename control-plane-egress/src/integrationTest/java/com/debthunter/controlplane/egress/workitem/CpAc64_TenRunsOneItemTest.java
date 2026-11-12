package com.debthunter.controlplane.egress.workitem;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.egress.outbox.InMemoryEgressCheckpoints;
import com.debthunter.domain.Severity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CP-AC-64: ten runs that all contain the finding leave one work item. */
@Tag("integration")
class CpAc64_TenRunsOneItemTest {

  @Test
  void tenRunsContainingTheFindingLeaveOneItem() {
    WorkItemHarness h = JdbcStores.harness();
    for (int run = 1; run <= 10; run++) {
      h.run("run-" + run, WorkItemHarness.finding("fp-1", Severity.HIGH));
      if (run == 1) {
        h.firstSeen("fp-1", "run-1");
      }
      if (run == 2) {
        h.opened("fp-1", "run-2");
      }
      // Runs 3 to 10 see it open again; the lifecycle records no transition for them.
      h.pump();
    }

    assertThat(h.tracker.creates.get()).isEqualTo(1);
    assertThat(h.tracker.all()).hasSize(1);
  }

  @Test
  void replayingTheWholeLogManyTimesStillLeavesOneItem() {
    WorkItemHarness h = JdbcStores.harness();
    h.run("run-1", WorkItemHarness.finding("fp-1", Severity.HIGH));
    h.firstSeen("fp-1", "run-1");
    h.opened("fp-1", "run-1");
    h.pump();

    for (int i = 0; i < 9; i++) {
      // The feeder's position is lost nine times over: the log is read from the start each time.
      WorkItemFeeder replay =
          new WorkItemFeeder(h.stores.log(), new InMemoryEgressCheckpoints(), h.outbox, 50);
      replay.feed();
      h.pump();
    }

    assertThat(h.tracker.creates.get()).isEqualTo(1);
    assertThat(h.tracker.all()).hasSize(1);
    assertThat(h.tracker.moves.get()).as("an item already in line is left alone").isZero();
  }
}
