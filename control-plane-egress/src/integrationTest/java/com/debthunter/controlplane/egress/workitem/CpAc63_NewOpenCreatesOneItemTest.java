package com.debthunter.controlplane.egress.workitem;

import static com.debthunter.controlplane.egress.workitem.WorkItemHarness.REPO;
import static com.debthunter.controlplane.egress.workitem.WorkItemHarness.SLICE;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.domain.Severity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CP-AC-63: a finding that becomes open at stage 3 gets exactly one work item. */
@Tag("integration")
class CpAc63_NewOpenCreatesOneItemTest {

  @Test
  void aFindingThatBecomesOpenGetsOneItemRoutedAndLinked() {
    WorkItemHarness h = JdbcStores.harness();
    h.run("run-1", WorkItemHarness.finding("fp-1", Severity.HIGH));
    h.firstSeen("fp-1", "run-1");
    h.pump();
    assertThat(h.tracker.all()).as("a first sighting is not yet an item").isEmpty();

    h.opened("fp-1", "run-1");
    h.pump();

    assertThat(h.tracker.all()).hasSize(1);
    FakeTracker.Item item = h.tracker.all().get(0);
    assertThat(item.route.target()).isEqualTo("DH");
    assertThat(item.status).isEqualTo(WorkItemStatus.OPEN);
    WorkItemLink link = h.stores.links().find(REPO, SLICE, "fp-1").orElseThrow();
    assertThat(link.itemId()).isEqualTo(item.id);
    assertThat(link.itemKey()).isEqualTo(WorkItemKey.of(REPO, SLICE, "fp-1"));
    assertThat(h.stores.queue().counts(WorkItemQueue.KIND).pending()).isZero();
    assertThat(h.alerts.alerts).isEmpty();
  }
}
