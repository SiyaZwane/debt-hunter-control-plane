package com.debthunter.controlplane.ingest.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.debthunter.controlplane.ingest.receive.Handlers;
import com.debthunter.controlplane.ingest.receive.InMemoryRunBundleDeduplicator;
import com.debthunter.controlplane.ingest.receive.RunBundleHandler;
import com.debthunter.controlplane.ingest.receive.RunBundleReceiver;
import com.debthunter.controlplane.store.queue.AesGcmPayloadCipher;
import com.debthunter.controlplane.store.queue.EnqueueResult;
import com.debthunter.controlplane.store.queue.InMemoryWorkQueue;
import com.debthunter.controlplane.store.queue.QueueCounts;
import com.debthunter.controlplane.store.queue.QueueEntry;
import com.debthunter.controlplane.store.queue.QueuedItem;
import com.debthunter.controlplane.store.queue.RetryOutcome;
import com.debthunter.controlplane.store.queue.WorkQueue;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DurableIntakeWorkerTest {

  private static final byte[] KEY = new byte[32];

  static {
    Arrays.fill(KEY, (byte) 5);
  }

  private final MutableClock clock = new MutableClock();
  private final InMemoryWorkQueue queue =
      new InMemoryWorkQueue(
          IntakeQueue.policies(
              3, Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(4)),
          clock);
  private final AesGcmPayloadCipher cipher = new AesGcmPayloadCipher(KEY);
  private final DurableRunIntakePublisher publisher =
      new DurableRunIntakePublisher(queue, cipher, new IntakeEnvelopeCodec());
  private final List<String> processed = new CopyOnWriteArrayList<>();

  /** A clock the test moves by hand. */
  private static final class MutableClock extends Clock {
    private volatile Instant now = Instant.parse("2026-05-01T10:00:00Z");

    void advance(Duration d) {
      now = now.plus(d);
    }

    @Override
    public java.time.ZoneId getZone() {
      return java.time.ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }

    @Override
    public Instant instant() {
      return now;
    }
  }

  private DurableIntakeWorker worker(RunBundleHandler handler) {
    return worker(queue, handler);
  }

  private DurableIntakeWorker worker(WorkQueue q, RunBundleHandler handler) {
    return new DurableIntakeWorker(
        q,
        cipher,
        new IntakeEnvelopeCodec(),
        new RunBundleReceiver(new InMemoryRunBundleDeduplicator(100), handler),
        "w1",
        10,
        Duration.ofMillis(10));
  }

  private void publish(String runId, String repository) throws Exception {
    publisher.publish(IntakeEnvelopes.forRun(runId, repository)).toCompletableFuture().get();
  }

  @Test
  void aBundleIsHandedToTheReceiverAndTheItemIsFinishedWithItsPayloadErased() throws Exception {
    publish("r1", "acme/a");
    DurableIntakeWorker w = worker(Handlers.processing(e -> processed.add(e.runId())));

    int taken = w.runOnce();

    assertThat(taken).isEqualTo(1);
    assertThat(processed).containsExactly("r1");
    assertThat(queue.counts(IntakeQueue.KIND)).isEqualTo(new QueueCounts(0, 0, 1, 0));
    assertThat(queue.find(1).orElseThrow().payloadBytes()).isZero();
  }

  @Test
  void anEmptyQueueTakesNothing() {
    assertThat(worker(Handlers.processing(e -> {})).runOnce()).isZero();
  }

  @Test
  void aRepositorysBundlesAreHandledInTheOrderTheyArrived() throws Exception {
    publish("r1", "acme/a");
    publish("r2", "acme/a");
    publish("r3", "acme/a");
    DurableIntakeWorker w = worker(Handlers.processing(e -> processed.add(e.runId())));

    while (w.runOnce() > 0) {
      // keep going until the queue is drained
    }

    assertThat(processed).containsExactly("r1", "r2", "r3");
  }

  @Test
  void aFailureIsRetriedAfterABackoffAndTheLaterBundleWaitsBehindIt() throws Exception {
    publish("r1", "acme/a");
    publish("r2", "acme/a");
    AtomicInteger calls = new AtomicInteger();
    DurableIntakeWorker w =
        worker(
            Handlers.processing(
                e -> {
                  if (e.runId().equals("r1") && calls.incrementAndGet() == 1) {
                    throw new IllegalStateException("database blip with alice@example.com in it");
                  }
                  processed.add(e.runId());
                }));

    w.runOnce();
    assertThat(processed).as("r2 waits behind the failing r1").isEmpty();
    assertThat(queue.find(1).orElseThrow().lastError())
        .as("only the exception type is kept, never its message")
        .isEqualTo("failed: IllegalStateException");

    clock.advance(Duration.ofSeconds(2));
    w.runOnce();
    w.runOnce();

    assertThat(processed).containsExactly("r1", "r2");
  }

  @Test
  void aBundleThatKeepsFailingIsSetAsideAfterItsAttemptsAndItsRepositoryCarriesOn()
      throws Exception {
    publish("poison", "acme/a");
    publish("fine", "acme/a");
    DurableIntakeWorker w =
        worker(
            Handlers.processing(
                e -> {
                  if (e.runId().equals("poison")) {
                    throw new IllegalStateException("cannot");
                  }
                  processed.add(e.runId());
                }));

    for (int i = 0; i < 3; i++) {
      w.runOnce();
      clock.advance(Duration.ofSeconds(10));
    }
    w.runOnce();

    assertThat(queue.find(1).orElseThrow().status()).isEqualTo(QueueEntry.Status.DEAD);
    assertThat(queue.find(1).orElseThrow().attempts()).isEqualTo(3);
    assertThat(processed).containsExactly("fine");
  }

  @Test
  void aKeyStoreThatIsDownHoldsTheBundleForeverAndNeverSetsItAside() throws Exception {
    publish("r1", "acme/a");
    java.util.concurrent.atomic.AtomicBoolean up = new java.util.concurrent.atomic.AtomicBoolean();
    DurableIntakeWorker w =
        worker(
            Handlers.processing(
                e -> {
                  if (!up.get()) {
                    throw new com.debthunter.controlplane.ingest.pseudonymise
                        .KeyStoreUnavailableException("vault down");
                  }
                  processed.add(e.runId());
                }));

    for (int i = 0; i < 25; i++) {
      w.runOnce();
      clock.advance(Duration.ofSeconds(10));
    }
    assertThat(queue.find(1).orElseThrow().status()).isEqualTo(QueueEntry.Status.PENDING);
    assertThat(queue.find(1).orElseThrow().attempts()).isZero();
    assertThat(processed).isEmpty();

    up.set(true);
    w.runOnce();

    assertThat(processed).containsExactly("r1");
  }

  @Test
  void aDatabaseFailureInsideTheReceiverIsAlsoAHoldEvenWhenWrapped() throws Exception {
    publish("r1", "acme/a");
    DurableIntakeWorker w =
        worker(
            Handlers.processing(
                e -> {
                  throw new IllegalStateException(
                      "could not store",
                      new com.debthunter.controlplane.store.jdbc.StoreException("db down", null));
                }));

    for (int i = 0; i < 10; i++) {
      w.runOnce();
      clock.advance(Duration.ofSeconds(10));
    }

    assertThat(queue.find(1).orElseThrow().status()).isEqualTo(QueueEntry.Status.PENDING);
  }

  @Test
  void anOrdinaryBugIsNotMistakenForAnOutage() {
    assertThat(DurableIntakeWorker.isInfrastructureFailure(new IllegalStateException("bug")))
        .isFalse();
    assertThat(
            DurableIntakeWorker.isInfrastructureFailure(
                new RuntimeException(new IllegalArgumentException("also a bug"))))
        .isFalse();
  }

  @Test
  void aBundleThatCannotBeDecryptedIsSetAsideAtOnceWithoutAttempts() throws Exception {
    byte[] wrongKey = new byte[32];
    Arrays.fill(wrongKey, (byte) 99);
    new DurableRunIntakePublisher(
            queue, new AesGcmPayloadCipher(wrongKey), new IntakeEnvelopeCodec())
        .publish(IntakeEnvelopes.forRun("r1"))
        .toCompletableFuture()
        .get();
    DurableIntakeWorker w = worker(Handlers.processing(e -> processed.add(e.runId())));

    w.runOnce();

    assertThat(processed).isEmpty();
    QueueEntry entry = queue.find(1).orElseThrow();
    assertThat(entry.status()).isEqualTo(QueueEntry.Status.DEAD);
    assertThat(entry.lastError()).startsWith("unreadable:");
  }

  @Test
  void aBundleTheReceiverSetsAsideKeepsItsEncryptedPayloadForInvestigation() throws Exception {
    publish("r1", "acme/a");
    DurableIntakeWorker w = worker(envelope -> RunBundleHandler.Disposition.SET_ASIDE);

    w.runOnce();

    QueueEntry entry = queue.find(1).orElseThrow();
    assertThat(entry.status()).isEqualTo(QueueEntry.Status.DEAD);
    assertThat(entry.payloadBytes()).isPositive();
    assertThat(entry.lastError()).contains("set aside by the pipeline");
    assertThat(queue.counts(IntakeQueue.KIND).done()).isZero();
  }

  @Test
  void aBundleDeliveredTwiceIsProcessedOnce() throws Exception {
    publish("r1", "acme/a");
    publish("r1", "acme/a");
    DurableIntakeWorker w = worker(Handlers.processing(e -> processed.add(e.runId())));

    while (w.runOnce() > 0) {
      // drain
    }

    assertThat(processed).containsExactly("r1");
    assertThat(queue.counts(IntakeQueue.KIND).done()).isEqualTo(2);
  }

  @Test
  void aDifferentRepositoryIsNotHeldUpByAFailingOne() throws Exception {
    publish("bad", "acme/a");
    publish("good", "acme/b");
    DurableIntakeWorker w =
        worker(
            Handlers.processing(
                e -> {
                  if (e.runId().equals("bad")) {
                    throw new IllegalStateException("x");
                  }
                  processed.add(e.runId());
                }));

    w.runOnce();

    assertThat(processed).containsExactly("good");
  }

  // ---- the background thread -----------------------------------------------------------------

  @Test
  void theBackgroundWorkerDrainsTheQueueAndStopsWhenClosed() throws Exception {
    publish("r1", "acme/a");
    publish("r2", "acme/b");
    DurableIntakeWorker w = worker(Handlers.processing(e -> processed.add(e.runId())));

    w.start();
    w.start();
    await().atMost(Duration.ofSeconds(10)).until(() -> processed.size() == 2);
    w.close();
    w.close();

    publish("r3", "acme/a");
    Thread.sleep(100);
    assertThat(processed).as("nothing is taken after close").hasSize(2);
  }

  /** A queue whose claim fails the first few times. */
  private final class FlakyQueue implements WorkQueue {
    final AtomicInteger failures = new AtomicInteger(2);

    @Override
    public EnqueueResult enqueue(String k, String key, String d, byte[] p, Instant nb) {
      return queue.enqueue(k, key, d, p, nb);
    }

    @Override
    public List<QueuedItem> claim(String k, String w, int max) {
      if (failures.getAndDecrement() > 0) {
        throw new IllegalStateException("database down");
      }
      return queue.claim(k, w, max);
    }

    @Override
    public boolean complete(long id, String w) {
      return queue.complete(id, w);
    }

    @Override
    public RetryOutcome retry(long id, String w, String e) {
      return queue.retry(id, w, e);
    }

    @Override
    public boolean hold(long id, String w, String r) {
      return queue.hold(id, w, r);
    }

    @Override
    public boolean deadLetter(long id, String w, String e) {
      return queue.deadLetter(id, w, e);
    }

    @Override
    public int purge(Instant a, Instant b) {
      return queue.purge(a, b);
    }

    @Override
    public Optional<QueueEntry> find(long id) {
      return queue.find(id);
    }

    @Override
    public QueueCounts counts(String k) {
      return queue.counts(k);
    }
  }

  @Test
  void theWorkerSurvivesTheDatabaseBeingDownAndCarriesOnWhenItReturns() throws Exception {
    publish("r1", "acme/a");
    DurableIntakeWorker w =
        worker(new FlakyQueue(), Handlers.processing(e -> processed.add(e.runId())));

    w.start();
    await().atMost(Duration.ofSeconds(10)).until(() -> !processed.isEmpty());
    w.close();

    assertThat(processed).containsExactly("r1");
  }

  @Test
  void aWorkerNeedsAPositiveBatchSize() {
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new DurableIntakeWorker(
                    queue,
                    cipher,
                    new IntakeEnvelopeCodec(),
                    new RunBundleReceiver(
                        new InMemoryRunBundleDeduplicator(1), Handlers.processing(e -> {})),
                    "w",
                    0,
                    Duration.ofMillis(10)));
  }
}
