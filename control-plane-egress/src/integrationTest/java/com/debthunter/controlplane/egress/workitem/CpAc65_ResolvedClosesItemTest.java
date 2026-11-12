package com.debthunter.controlplane.egress.workitem;

import static com.debthunter.controlplane.egress.workitem.WorkItemHarness.REPO;
import static com.debthunter.controlplane.egress.workitem.WorkItemHarness.SLICE;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.domain.Severity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CP-AC-65: when the finding is resolved its work item is closed. */
@Tag("integration")
class CpAc65_ResolvedClosesItemTest {

  @Test
  void resolvingTheFindingClosesTheItem() {
    WorkItemHarness h = JdbcStores.harness();
    h.run("run-1", WorkItemHarness.finding("fp-1", Severity.CRITICAL));
    h.firstSeen("fp-1", "run-1");
    h.opened("fp-1", "run-1");
    h.pump();

    h.resolved("fp-1", "run-2");
    h.pump();

    assertThat(h.tracker.all()).hasSize(1);
    FakeTracker.Item item = h.tracker.all().get(0);
    assertThat(item.status).isEqualTo(WorkItemStatus.CLOSED);
    assertThat(item.comments).hasSize(1);
    assertThat(h.stores.links().find(REPO, SLICE, "fp-1").orElseThrow().status())
        .isEqualTo(WorkItemStatus.CLOSED);
  }
}
