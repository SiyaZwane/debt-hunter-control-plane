package com.debthunter.controlplane.egress.workitem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.egress.MutableClock;
import com.debthunter.controlplane.egress.outbox.TransactionalOutbox;
import com.debthunter.controlplane.store.queue.EnqueueResult;
import com.debthunter.controlplane.store.queue.QueueCounts;
import com.debthunter.controlplane.store.queue.QueueEntry;
import com.debthunter.controlplane.store.queue.QueuedItem;
import com.debthunter.controlplane.store.queue.RetryOutcome;
import com.debthunter.controlplane.store.queue.WorkQueue;
import com.debthunter.domain.Severity;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

/**
 * CP-BC-13: when the outbox cannot be written to, the transition that needed an item is neither
 * lost nor treated as handled. The feeder stops where it was, says so to its caller, and offers the
 * same events again once the outbox works.
 */
class CpBc13_OutboxWriteFailureLosesNothingTest {

  /** A queue that refuses to enqueue while {@code failing} is set, and otherwise works. */
  private static final class FlakyQueue implements WorkQueue {
    private final WorkQueue real;
    final AtomicBoolean failing = new AtomicBoolean();

    FlakyQueue(WorkQueue real) {
      this.real = real;
    }

    @Override
    public EnqueueResult enqueue(
        String kind, String key, String dedupKey, byte[] payload, Instant notBefore) {
      if (failing.get()) {
        throw new IllegalStateException("the store is down");
      }
      return real.enqueue(kind, key, dedupKey, payload, notBefore);
    }

    @Override
    public List<QueuedItem> claim(String kind, String worker, int max) {
      return real.claim(kind, worker, max);
    }

    @Override
    public boolean complete(long id, String worker) {
      return real.complete(id, worker);
    }

    @Override
    public RetryOutcome retry(long id, String worker, String error) {
      return real.retry(id, worker, error);
    }

    @Override
    public boolean hold(long id, String worker, String reason) {
      return real.hold(id, worker, reason);
    }

    @Override
    public boolean defer(long id, String worker, Instant notBefore) {
      return real.defer(id, worker, notBefore);
    }

    @Override
    public int countStarted(String kind, String key, Instant finishedSince, long excludeId) {
      return real.countStarted(kind, key, finishedSince, excludeId);
    }

    @Override
    public boolean deadLetter(long id, String worker, String error) {
      return real.deadLetter(id, worker, error);
    }

    @Override
    public int purge(Instant doneBefore, Instant deadBefore) {
      return real.purge(doneBefore, deadBefore);
    }

    @Override
    public Optional<QueueEntry> find(long id) {
      return real.find(id);
    }

    @Override
    public QueueCounts counts(String kind) {
      return real.counts(kind);
    }
  }

  @Test
  void aFailedWriteLeavesTheFeederWhereItWasAndNothingIsLostWhenItRecovers() {
    MutableClock clock = new MutableClock();
    WorkItemHarness.Stores plain = InMemoryStores.create(clock);
    FlakyQueue flaky = new FlakyQueue(plain.queue());
    WorkItemHarness.Stores stores =
        new WorkItemHarness.Stores(
            plain.log(),
            plain.findings(),
            plain.suppressions(),
            plain.registry(),
            plain.links(),
            plain.checkpoints(),
            flaky);
    WorkItemHarness h = new WorkItemHarness(stores, clock);
    h.run("run-1", WorkItemHarness.finding("fp-1", Severity.HIGH));
    h.firstSeen("fp-1", "run-1");
    h.opened("fp-1", "run-1");

    flaky.failing.set(true);
    assertThatThrownBy(h.feeder::feed).hasMessageContaining("store is down");
    assertThat(stores.checkpoints().read(WorkItemFeeder.CHECKPOINT))
        .as("the feeder did not move past events it could not hand on")
        .isZero();
    assertThat(h.tracker.all()).isEmpty();

    flaky.failing.set(false);
    h.pump();

    assertThat(h.tracker.all()).hasSize(1);
    assertThat(h.tracker.all().get(0).status).isEqualTo(WorkItemStatus.OPEN);
    assertThat(stores.checkpoints().read(WorkItemFeeder.CHECKPOINT)).isPositive();
  }

  @Test
  void aFailureHalfwayThroughABatchOffersTheWholeBatchAgainWithoutDoubling() {
    MutableClock clock = new MutableClock();
    WorkItemHarness.Stores plain = InMemoryStores.create(clock);
    var counting = new FlakyQueue(plain.queue());
    WorkItemHarness.Stores stores =
        new WorkItemHarness.Stores(
            plain.log(),
            plain.findings(),
            plain.suppressions(),
            plain.registry(),
            plain.links(),
            plain.checkpoints(),
            counting);
    WorkItemHarness h = new WorkItemHarness(stores, clock);
    h.run("run-1", WorkItemHarness.finding("fp-1", Severity.HIGH));
    h.run("run-2", WorkItemHarness.finding("fp-2", Severity.HIGH));
    h.firstSeen("fp-1", "run-1");
    h.opened("fp-1", "run-1");
    h.firstSeen("fp-2", "run-2");
    h.opened("fp-2", "run-2");

    // The outbox accepts one message, then the store goes down in the middle of the batch.
    TransactionalOutbox once =
        new TransactionalOutbox(
            new WorkQueue() {
              int accepted;

              @Override
              public EnqueueResult enqueue(
                  String kind, String key, String dedupKey, byte[] payload, Instant notBefore) {
                if (accepted++ >= 1) {
                  throw new IllegalStateException("the store went down");
                }
                return counting.enqueue(kind, key, dedupKey, payload, notBefore);
              }

              @Override
              public List<QueuedItem> claim(String kind, String worker, int max) {
                return counting.claim(kind, worker, max);
              }

              @Override
              public boolean complete(long id, String worker) {
                return counting.complete(id, worker);
              }

              @Override
              public RetryOutcome retry(long id, String worker, String error) {
                return counting.retry(id, worker, error);
              }

              @Override
              public boolean hold(long id, String worker, String reason) {
                return counting.hold(id, worker, reason);
              }

              @Override
              public boolean defer(long id, String worker, Instant notBefore) {
                return counting.defer(id, worker, notBefore);
              }

              @Override
              public int countStarted(String kind, String key, Instant since, long exclude) {
                return counting.countStarted(kind, key, since, exclude);
              }

              @Override
              public boolean deadLetter(long id, String worker, String error) {
                return counting.deadLetter(id, worker, error);
              }

              @Override
              public int purge(Instant doneBefore, Instant deadBefore) {
                return counting.purge(doneBefore, deadBefore);
              }

              @Override
              public Optional<QueueEntry> find(long id) {
                return counting.find(id);
              }

              @Override
              public QueueCounts counts(String kind) {
                return counting.counts(kind);
              }
            },
            WorkItemQueue.KIND,
            "w1",
            10,
            h.alerts,
            clock);
    WorkItemFeeder partial = new WorkItemFeeder(stores.log(), stores.checkpoints(), once, 50);

    assertThatThrownBy(partial::feed).hasMessageContaining("went down");
    assertThat(stores.checkpoints().read(WorkItemFeeder.CHECKPOINT)).isZero();

    h.pump();

    assertThat(h.tracker.all()).hasSize(2);
    assertThat(h.tracker.creates.get()).isEqualTo(2);
  }
}
