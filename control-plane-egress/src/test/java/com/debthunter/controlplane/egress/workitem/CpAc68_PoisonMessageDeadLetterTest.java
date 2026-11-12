package com.debthunter.controlplane.egress.workitem;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.egress.alert.OperationsAlertType;
import com.debthunter.controlplane.store.queue.QueueEntry;
import com.debthunter.domain.Severity;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-68: a message that can never be delivered is set aside, an alert is raised, and nothing is
 * discarded silently. Messages for other findings carry on.
 */
class CpAc68_PoisonMessageDeadLetterTest {

  @Test
  void aMessageThatIsNotAJobIsSetAsideAtOnceWithAnAlertAndKept() {
    WorkItemHarness h = InMemoryStores.harness();
    long id =
        h.outbox.submit("garbage-key", null, "{not json".getBytes(StandardCharsets.UTF_8)).id();

    h.pump();

    QueueEntry entry = h.stores.queue().find(id).orElseThrow();
    assertThat(entry.status()).isEqualTo(QueueEntry.Status.DEAD);
    assertThat(entry.payloadBytes()).isPositive();
    assertThat(h.alerts.alerts).hasSize(1);
    assertThat(h.alerts.alerts.get(0).type()).isEqualTo(OperationsAlertType.MESSAGE_DEAD_LETTERED);
    assertThat(h.alerts.alerts.get(0).key()).isEqualTo("garbage-key");
    assertThat(h.alerts.alerts.get(0).kind()).isEqualTo(WorkItemQueue.KIND);
  }

  @Test
  void aTrackerThatKeepsRefusingEndsInADeadLetterAndAnAlertNeverASilentDrop() {
    WorkItemHarness h = InMemoryStores.harness();
    h.run("run-1", WorkItemHarness.finding("fp-1", Severity.HIGH));
    h.firstSeen("fp-1", "run-1");
    h.opened("fp-1", "run-1");
    h.tracker.refuseCreate = true;

    for (int i = 0; i < 6; i++) {
      h.pump();
      h.clock.advance(Duration.ofSeconds(8));
    }

    var counts = h.stores.queue().counts(WorkItemQueue.KIND);
    assertThat(counts.dead()).isEqualTo(1);
    assertThat(counts.pending()).isZero();
    assertThat(h.alerts.alerts).hasSize(1);
    assertThat(h.alerts.alerts.get(0).reason()).contains("TrackerRejectedException");
    assertThat(h.tracker.all()).isEmpty();
  }

  @Test
  void aPoisonMessageDoesNotHoldUpAnotherFindingsWork() {
    WorkItemHarness h = InMemoryStores.harness();
    h.outbox.submit("poison", null, "garbage".getBytes(StandardCharsets.UTF_8));
    h.run("run-1", WorkItemHarness.finding("fp-1", Severity.HIGH));
    h.firstSeen("fp-1", "run-1");
    h.opened("fp-1", "run-1");

    h.pump();

    assertThat(h.tracker.all()).hasSize(1);
    assertThat(h.alerts.alerts).hasSize(1);
  }
}
