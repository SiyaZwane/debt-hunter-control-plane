package com.debthunter.controlplane.egress.workitem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.domain.Severity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class WorkItemFeederTest {

  private WorkItemHarness h;

  @BeforeEach
  void setUp() {
    h = InMemoryStores.harness();
    h.run("run-1", WorkItemHarness.finding("fp-1", Severity.HIGH));
  }

  private long queued() {
    var counts = h.stores.queue().counts(WorkItemQueue.KIND);
    return counts.pending();
  }

  @Test
  void aFirstSightingIsReadButQueuesNothing() {
    h.firstSeen("fp-1", "run-1");

    assertThat(h.feeder.feed()).isEqualTo(1);
    assertThat(queued()).isZero();
  }

  @Test
  void eachStateAnItemReactsToQueuesOneJob() {
    h.firstSeen("fp-1", "run-1");
    h.opened("fp-1", "run-1");
    h.suppressed("fp-1", "run-1");
    h.suppressionExpired("fp-1", "run-1");
    h.resolved("fp-1", "run-1");
    h.regressed("fp-1", "run-1");

    assertThat(h.feeder.feed()).isEqualTo(6);

    assertThat(queued()).isEqualTo(5);
  }

  @Test
  void theFeederResumesWhereItStoppedAndReadsNothingTwice() {
    h.firstSeen("fp-1", "run-1");
    h.opened("fp-1", "run-1");
    assertThat(h.feeder.feed()).isEqualTo(2);
    assertThat(h.feeder.feed()).isZero();

    h.resolved("fp-1", "run-2");

    assertThat(h.feeder.feed()).isEqualTo(1);
    assertThat(queued()).isEqualTo(2);
  }

  @Test
  void aBatchIsNoLargerThanConfigured() {
    var small = new WorkItemFeeder(h.stores.log(), h.stores.checkpoints(), h.outbox, 2);
    h.firstSeen("fp-1", "run-1");
    h.opened("fp-1", "run-1");
    h.resolved("fp-1", "run-1");

    assertThat(small.feed()).isEqualTo(2);
    assertThat(small.feed()).isEqualTo(1);
    assertThat(small.feed()).isZero();
  }

  @Test
  void feedingTheSameEventsAgainAfterACrashDoesNotDoubleTheJobs() {
    h.firstSeen("fp-1", "run-1");
    h.opened("fp-1", "run-1");
    h.feeder.feed();
    // The position is lost (a crash after queuing but before saving it).
    var rewound =
        new WorkItemFeeder(
            h.stores.log(),
            new com.debthunter.controlplane.egress.outbox.InMemoryEgressCheckpoints(),
            h.outbox,
            50);

    rewound.feed();

    assertThat(queued()).as("the waiting job is not queued a second time").isEqualTo(1);
  }

  @Test
  void aBatchSizeBelowOneIsRefused() {
    assertThatThrownBy(
            () -> new WorkItemFeeder(h.stores.log(), h.stores.checkpoints(), h.outbox, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void jobsForOneFindingShareAKeySoTheyAreHandledInOrder() {
    SyncJob a = new SyncJob("r", "s", "fp", "run-1", "e1");
    SyncJob b = new SyncJob("r", "s", "fp", "run-2", "e2");
    SyncJob other = new SyncJob("r", "s", "fp-2", "run-1", "e3");

    assertThat(a.queueKey()).isEqualTo(b.queueKey()).isNotEqualTo(other.queueKey());
  }
}
