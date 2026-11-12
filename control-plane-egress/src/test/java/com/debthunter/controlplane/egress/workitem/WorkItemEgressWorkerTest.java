package com.debthunter.controlplane.egress.workitem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.debthunter.domain.Severity;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class WorkItemEgressWorkerTest {

  @Test
  void aPassFeedsTheLogAndDeliversWhatItQueued() {
    WorkItemHarness h = InMemoryStores.harness();
    h.run("run-1", WorkItemHarness.finding("fp-1", Severity.HIGH));
    h.firstSeen("fp-1", "run-1");
    h.opened("fp-1", "run-1");

    int worked = h.worker.runOnce();

    assertThat(worked).isPositive();
    assertThat(h.tracker.all()).hasSize(1);
    assertThat(h.worker.runOnce()).as("nothing more to do").isZero();
  }

  @Test
  void theBackgroundThreadPicksUpWorkThatArrivesLaterAndStopsWhenClosed() {
    WorkItemHarness h = InMemoryStores.harness();
    h.run("run-1", WorkItemHarness.finding("fp-1", Severity.HIGH));
    h.worker.start();
    h.worker.start(); // starting again changes nothing

    h.firstSeen("fp-1", "run-1");
    h.opened("fp-1", "run-1");

    await().atMost(Duration.ofSeconds(10)).until(() -> h.tracker.creates.get() == 1);
    h.worker.close();
    h.worker.close(); // closing again changes nothing

    h.resolved("fp-1", "run-2");
    try {
      Thread.sleep(100);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    assertThat(h.tracker.all().get(0).status)
        .as("a stopped worker leaves the work queued, not lost")
        .isEqualTo(WorkItemStatus.OPEN);
    assertThat(h.worker.runOnce()).isPositive();
    assertThat(h.tracker.all().get(0).status).isEqualTo(WorkItemStatus.CLOSED);
  }

  @Test
  void aPassThatFailsDoesNotKillTheThread() {
    WorkItemHarness h = InMemoryStores.harness();
    h.run("run-1", WorkItemHarness.finding("fp-1", Severity.HIGH));
    h.firstSeen("fp-1", "run-1");
    h.opened("fp-1", "run-1");
    java.util.concurrent.atomic.AtomicInteger failures =
        new java.util.concurrent.atomic.AtomicInteger();
    WorkItemFeeder feeder = h.feeder;
    WorkItemEgressWorker worker =
        new WorkItemEgressWorker(
            feeder,
            h.outbox,
            m -> {
              if (failures.getAndIncrement() == 0) {
                throw new IllegalStateException("first delivery blows up");
              }
              new WorkItemSyncHandler(h.orchestrator).deliver(m);
            },
            Duration.ofMillis(10));

    worker.runOnce();
    h.clock.advance(Duration.ofSeconds(8));
    worker.runOnce();

    assertThat(h.tracker.all()).hasSize(1);
    worker.close();
  }
}
