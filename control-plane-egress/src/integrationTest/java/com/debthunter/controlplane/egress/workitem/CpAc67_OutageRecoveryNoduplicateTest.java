package com.debthunter.controlplane.egress.workitem;

import static com.debthunter.controlplane.egress.workitem.WorkItemHarness.REPO;
import static com.debthunter.controlplane.egress.workitem.WorkItemHarness.SLICE;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.domain.Severity;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-67: after the tracker has been down, or a worker died part-way, recovery produces exactly
 * one item and loses nothing.
 */
@Tag("integration")
class CpAc67_OutageRecoveryNoduplicateTest {

  private static WorkItemHarness openFinding() {
    WorkItemHarness h = JdbcStores.harness();
    h.run("run-1", WorkItemHarness.finding("fp-1", Severity.HIGH));
    h.firstSeen("fp-1", "run-1");
    h.opened("fp-1", "run-1");
    return h;
  }

  @Test
  void aTrackerOutageHoldsTheWorkAndRecoveryCreatesOneItem() {
    WorkItemHarness h = openFinding();
    h.tracker.down = true;

    for (int i = 0; i < 8; i++) {
      h.pump();
      h.clock.advance(Duration.ofSeconds(8));
    }
    assertThat(h.tracker.all()).isEmpty();
    var held = h.stores.queue().counts(WorkItemQueue.KIND);
    assertThat(held.pending()).isEqualTo(1);
    assertThat(held.dead()).as("an outage never sets work aside").isZero();
    assertThat(h.alerts.alerts).isEmpty();

    h.tracker.down = false;
    h.pump();

    assertThat(h.tracker.creates.get()).isEqualTo(1);
    assertThat(h.tracker.all()).hasSize(1);
    assertThat(h.stores.queue().counts(WorkItemQueue.KIND).pending()).isZero();
  }

  @Test
  void aWorkerThatDiedAfterCreatingTheItemDoesNotCauseASecondOne() {
    WorkItemHarness h = openFinding();
    h.tracker.failAfterNextCreate = true;

    h.pump();
    assertThat(h.tracker.all()).as("the item exists but nobody recorded it").hasSize(1);
    assertThat(h.stores.links().find(REPO, SLICE, "fp-1").orElseThrow().hasItem()).isFalse();

    h.clock.advance(Duration.ofSeconds(8));
    h.pump();

    assertThat(h.tracker.creates.get()).isEqualTo(1);
    assertThat(h.stores.links().find(REPO, SLICE, "fp-1").orElseThrow().itemId())
        .isEqualTo(h.tracker.all().get(0).id);
    assertThat(h.stores.queue().counts(WorkItemQueue.KIND).dead()).isZero();
  }

  @Test
  void aWorkerThatDiedHoldingTheFindingLosesItsClaimWhenTheClaimLapses() {
    WorkItemHarness h = openFinding();
    h.stores
        .links()
        .reserve(
            REPO, SLICE, "fp-1", WorkItemKey.of(REPO, SLICE, "fp-1"), "fake", h.clock.instant());
    h.stores
        .links()
        .claim(
            REPO,
            SLICE,
            "fp-1",
            "dead-worker",
            h.clock.instant(),
            h.clock.instant().plus(h.settings.claimTtl()));

    h.pump();
    assertThat(h.tracker.all()).as("the dead worker's claim holds the finding for now").isEmpty();

    h.clock.advance(h.settings.claimTtl().plusSeconds(10));
    h.pump();

    assertThat(h.tracker.creates.get()).isEqualTo(1);
  }
}
