package com.debthunter.controlplane.egress.workitem;

import static com.debthunter.controlplane.egress.workitem.WorkItemHarness.REPO;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.registry.WorkItemRoute;
import com.debthunter.domain.Severity;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * CP-BC-12: a repository with no routing entry (or a route to a tracker this deployment has no
 * adapter for) must not lose its work items. They are held, attempts are not spent, and they are
 * delivered once a route exists.
 */
class CpBc12_MissingRoutingEntryHoldsTest {

  private static final String FP = "fp-1";

  @Test
  void aRepositoryWithNoRoutingEntryHasItsWorkHeldNotDropped() {
    WorkItemHarness h = InMemoryStores.harness();
    h.stores.registry().delete(REPO);
    h.run("run-1", WorkItemHarness.finding(FP, Severity.HIGH));
    h.firstSeen(FP, "run-1");
    h.opened(FP, "run-1");

    for (int i = 0; i < 12; i++) {
      h.pump();
      h.clock.advance(Duration.ofSeconds(8));
    }

    assertThat(h.tracker.calls).isEmpty();
    assertThat(h.alerts.alerts).as("held work is not dead work").isEmpty();
    var counts = h.stores.queue().counts(WorkItemQueue.KIND);
    assertThat(counts.dead()).isZero();
    assertThat(counts.pending()).isEqualTo(1);

    h.onboard(REPO, new WorkItemRoute(WorkItemHarness.ROUTE_TRACKER, "DH"));
    h.pump();

    assertThat(h.tracker.creates.get()).isEqualTo(1);
    assertThat(h.stores.queue().counts(WorkItemQueue.KIND).pending()).isZero();
  }

  @Test
  void aRouteToATrackerWithNoAdapterIsHeldToo() {
    WorkItemHarness h = InMemoryStores.harness();
    h.onboard(REPO, new WorkItemRoute("unknown-tracker", "DH"));
    h.run("run-1", WorkItemHarness.finding(FP, Severity.HIGH));
    h.firstSeen(FP, "run-1");
    h.opened(FP, "run-1");

    h.pump();

    assertThat(h.tracker.calls).isEmpty();
    assertThat(h.stores.queue().counts(WorkItemQueue.KIND).dead()).isZero();

    h.onboard(REPO, new WorkItemRoute(WorkItemHarness.ROUTE_TRACKER, "DH"));
    h.clock.advance(Duration.ofSeconds(8));
    h.pump();
    assertThat(h.tracker.creates.get()).isEqualTo(1);
  }

  @Test
  void heldWorkIsActedOnAsTheFindingIsWhenItIsFinallyDelivered() {
    WorkItemHarness h = InMemoryStores.harness();
    h.stores.registry().delete(REPO);
    h.run("run-1", WorkItemHarness.finding(FP, Severity.HIGH));
    h.firstSeen(FP, "run-1");
    h.opened(FP, "run-1");
    h.pump();
    h.resolved(FP, "run-2");
    h.pump();
    assertThat(h.tracker.calls).isEmpty();

    h.onboard(REPO, new WorkItemRoute(WorkItemHarness.ROUTE_TRACKER, "DH"));
    h.clock.advance(Duration.ofSeconds(8));
    h.pump();
    h.clock.advance(Duration.ofSeconds(8));
    h.pump();

    // The finding was resolved while its work was held, so no stale item is opened for it.
    assertThat(h.tracker.all()).isEmpty();
    var counts = h.stores.queue().counts(WorkItemQueue.KIND);
    assertThat(counts.pending()).isZero();
    assertThat(counts.dead()).isZero();
  }
}
