package com.debthunter.controlplane.egress.workitem;

import static com.debthunter.controlplane.egress.workitem.WorkItemHarness.REPO;
import static com.debthunter.controlplane.egress.workitem.WorkItemHarness.SLICE;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.domain.Severity;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CP-AC-66: a regression reopens the existing item; it never creates a second. */
@Tag("integration")
class CpAc66_RegressedReopensNotDuplicatesTest {

  @Test
  void aRegressionReopensTheSameItem() {
    WorkItemHarness h = JdbcStores.harness();
    h.run("run-1", WorkItemHarness.finding("fp-1", Severity.HIGH));
    h.firstSeen("fp-1", "run-1");
    h.opened("fp-1", "run-1");
    h.pump();
    h.resolved("fp-1", "run-2");
    h.pump();
    String itemId = h.stores.links().find(REPO, SLICE, "fp-1").orElseThrow().itemId();

    h.regressed("fp-1", "run-3");
    h.pump();

    assertThat(h.tracker.all()).hasSize(1);
    assertThat(h.tracker.creates.get()).isEqualTo(1);
    FakeTracker.Item item = h.tracker.all().get(0);
    assertThat(item.id).isEqualTo(itemId);
    assertThat(item.status).isEqualTo(WorkItemStatus.OPEN);
    assertThat(item.history)
        .containsExactly(WorkItemStatus.OPEN, WorkItemStatus.CLOSED, WorkItemStatus.OPEN);
  }

  @Test
  void aSuppressionThatExpiresReopensTheSameItemToo() {
    WorkItemHarness h = JdbcStores.harness();
    h.run("run-1", WorkItemHarness.finding("fp-1", Severity.HIGH));
    h.firstSeen("fp-1", "run-1");
    h.opened("fp-1", "run-1");
    h.pump();
    h.suppression("fp-1", "vendor patch pending", Duration.ofDays(14));
    h.suppressed("fp-1", "run-2");
    h.pump();
    assertThat(h.tracker.all().get(0).status).isEqualTo(WorkItemStatus.ON_HOLD);
    assertThat(h.tracker.all().get(0).comments.get(0)).contains("vendor patch pending");

    h.suppressionExpired("fp-1", "run-3");
    h.pump();

    assertThat(h.tracker.all()).hasSize(1);
    assertThat(h.tracker.all().get(0).status).isEqualTo(WorkItemStatus.OPEN);
  }
}
