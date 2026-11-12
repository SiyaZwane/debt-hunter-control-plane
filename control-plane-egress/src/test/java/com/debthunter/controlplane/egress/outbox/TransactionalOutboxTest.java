package com.debthunter.controlplane.egress.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.egress.MutableClock;
import com.debthunter.controlplane.egress.alert.OperationsAlertType;
import com.debthunter.controlplane.egress.alert.RecordingAlerts;
import com.debthunter.controlplane.store.queue.EnqueueResult;
import com.debthunter.controlplane.store.queue.InMemoryWorkQueue;
import com.debthunter.controlplane.store.queue.QueueEntry;
import com.debthunter.controlplane.store.queue.QueuePolicy;
import com.debthunter.controlplane.store.queue.WorkQueue;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** CP-AC-67 and CP-AC-68: at-least-once delivery, retried with backoff, never silently dropped. */
class TransactionalOutboxTest {

  private static final String KIND = "test-outbox";

  private final MutableClock clock = new MutableClock();
  private final RecordingAlerts alerts = new RecordingAlerts();
  private WorkQueue queue;
  private TransactionalOutbox outbox;

  @BeforeEach
  void setUp() {
    queue =
        new InMemoryWorkQueue(
            Map.of(
                KIND,
                new QueuePolicy(
                    true, 3, Duration.ofMinutes(5), Duration.ofSeconds(1), Duration.ofSeconds(8))),
            clock);
    outbox = new TransactionalOutbox(queue, KIND, "w1", 10, alerts, clock);
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  @Test
  void aSubmittedMessageIsDeliveredOnceAndFinished() {
    EnqueueResult result = outbox.submit("k", "idem-1", bytes("hello"));
    List<String> seen = new ArrayList<>();

    DrainResult drained =
        outbox.drain(m -> seen.add(new String(m.payload(), StandardCharsets.UTF_8)));

    assertThat(seen).containsExactly("hello");
    assertThat(drained).isEqualTo(new DrainResult(1, 0, 0, 0));
    assertThat(queue.find(result.id()).orElseThrow().status()).isEqualTo(QueueEntry.Status.DONE);
    assertThat(outbox.drain(m -> seen.add("again"))).isEqualTo(new DrainResult(0, 0, 0, 0));
  }

  @Test
  void anIdempotencyKeyStopsASecondCopyOfAWaitingMessage() {
    EnqueueResult first = outbox.submit("k", "idem-1", bytes("a"));
    EnqueueResult second = outbox.submit("k", "idem-1", bytes("a"));

    assertThat(second.duplicate()).isTrue();
    assertThat(second.id()).isEqualTo(first.id());
    List<String> seen = new ArrayList<>();
    outbox.drain(m -> seen.add("x"));
    assertThat(seen).hasSize(1);
  }

  @Test
  void aFailedDeliveryIsRetriedWithGrowingBackoff() {
    EnqueueResult r = outbox.submit("k", null, bytes("a"));
    OutboxHandler failing =
        m -> {
          throw new IllegalStateException("tracker said no");
        };

    assertThat(outbox.drain(failing)).isEqualTo(new DrainResult(0, 0, 1, 0));
    QueueEntry afterFirst = queue.find(r.id()).orElseThrow();
    assertThat(afterFirst.notBefore()).isEqualTo(clock.instant().plus(Duration.ofSeconds(1)));

    // Not due yet: nothing is tried.
    assertThat(outbox.drain(failing).total()).isZero();

    clock.advance(Duration.ofSeconds(1));
    assertThat(outbox.drain(failing)).isEqualTo(new DrainResult(0, 0, 1, 0));
    QueueEntry afterSecond = queue.find(r.id()).orElseThrow();
    assertThat(afterSecond.notBefore()).isEqualTo(clock.instant().plus(Duration.ofSeconds(2)));
    assertThat(alerts.alerts).isEmpty();
  }

  @Test
  void aMessageThatKeepsFailingIsSetAsideWithAnAlertAndItsPayloadKept() {
    EnqueueResult r = outbox.submit("finding-1", null, bytes("poison"));
    OutboxHandler failing =
        m -> {
          throw new IllegalStateException("boom");
        };

    outbox.drain(failing);
    clock.advance(Duration.ofSeconds(1));
    outbox.drain(failing);
    clock.advance(Duration.ofSeconds(2));
    DrainResult last = outbox.drain(failing);

    assertThat(last).isEqualTo(new DrainResult(0, 0, 0, 1));
    QueueEntry entry = queue.find(r.id()).orElseThrow();
    assertThat(entry.status()).isEqualTo(QueueEntry.Status.DEAD);
    assertThat(entry.payloadBytes()).as("kept for investigation, not discarded").isPositive();
    assertThat(alerts.alerts).hasSize(1);
    assertThat(alerts.alerts.get(0).type()).isEqualTo(OperationsAlertType.MESSAGE_DEAD_LETTERED);
    assertThat(alerts.alerts.get(0).key()).isEqualTo("finding-1");
    assertThat(alerts.alerts.get(0).itemId()).isEqualTo(r.id());
    assertThat(alerts.alerts.get(0).reason()).contains("boom").doesNotContain("poison");
  }

  @Test
  void aMessageThatCanNeverBeDeliveredIsSetAsideAtOnce() {
    EnqueueResult r = outbox.submit("k", null, bytes("garbage"));

    DrainResult drained =
        outbox.drain(
            m -> {
              throw new UndeliverableException("not a job");
            });

    assertThat(drained).isEqualTo(new DrainResult(0, 0, 0, 1));
    assertThat(queue.find(r.id()).orElseThrow().status()).isEqualTo(QueueEntry.Status.DEAD);
    assertThat(alerts.alerts).hasSize(1);
    assertThat(alerts.alerts.get(0).reason()).isEqualTo("not a job");
  }

  @Test
  void anUnavailableDestinationHoldsTheMessageForeverWithoutSettingItAside() {
    EnqueueResult r = outbox.submit("k", null, bytes("a"));
    OutboxHandler down =
        m -> {
          throw new DeliveryUnavailableException("tracker down", null);
        };

    for (int i = 0; i < 10; i++) {
      assertThat(outbox.drain(down)).isEqualTo(new DrainResult(0, 1, 0, 0));
      clock.advance(Duration.ofSeconds(8));
    }

    QueueEntry entry = queue.find(r.id()).orElseThrow();
    assertThat(entry.status()).isEqualTo(QueueEntry.Status.PENDING);
    assertThat(alerts.alerts).isEmpty();

    List<String> seen = new ArrayList<>();
    outbox.drain(m -> seen.add("delivered"));
    assertThat(seen).containsExactly("delivered");
  }

  @Test
  void messagesWithTheSameKeyAreDeliveredInOrderAndOnlyAfterTheEarlierOne() {
    outbox.submit("k", null, bytes("1"));
    outbox.submit("k", null, bytes("2"));
    List<String> seen = new ArrayList<>();
    OutboxHandler failFirst =
        m -> {
          String body = new String(m.payload(), StandardCharsets.UTF_8);
          seen.add(body);
          if (body.equals("1") && m.attempts() == 1) {
            throw new IllegalStateException("first try fails");
          }
        };

    outbox.drain(failFirst);
    assertThat(seen).containsExactly("1");

    clock.advance(Duration.ofSeconds(1));
    outbox.drain(failFirst);
    outbox.drain(failFirst);
    assertThat(seen).containsExactly("1", "1", "2");
  }

  @Test
  void aMessageForAnotherKeyIsNotHeldUpByAFailingOne() {
    outbox.submit("bad", null, bytes("1"));
    outbox.submit("good", null, bytes("2"));
    List<String> seen = new ArrayList<>();

    outbox.drain(
        m -> {
          if (m.key().equals("bad")) {
            throw new IllegalStateException("no");
          }
          seen.add(m.key());
        });

    assertThat(seen).containsExactly("good");
  }

  @Test
  void aSubmitThatCannotBeMadeDurableIsReportedNotSwallowed() {
    WorkQueue broken = new BrokenQueue();
    TransactionalOutbox failing = new TransactionalOutbox(broken, KIND, "w1", 10, alerts, clock);

    assertThatThrownBy(() -> failing.submit("k", "i", bytes("a")))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("store is down");
  }

  @Test
  void aBatchSizeBelowOneIsRefused() {
    assertThatThrownBy(() -> new TransactionalOutbox(queue, KIND, "w", 0, alerts, clock))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
