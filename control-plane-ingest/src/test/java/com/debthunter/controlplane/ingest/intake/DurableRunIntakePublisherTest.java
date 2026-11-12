package com.debthunter.controlplane.ingest.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.store.queue.AesGcmPayloadCipher;
import com.debthunter.controlplane.store.queue.EnqueueResult;
import com.debthunter.controlplane.store.queue.InMemoryWorkQueue;
import com.debthunter.controlplane.store.queue.QueueCounts;
import com.debthunter.controlplane.store.queue.QueueEntry;
import com.debthunter.controlplane.store.queue.QueuedItem;
import com.debthunter.controlplane.store.queue.RetryOutcome;
import com.debthunter.controlplane.store.queue.WorkQueue;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import org.junit.jupiter.api.Test;

class DurableRunIntakePublisherTest {

  private static final byte[] KEY = new byte[32];

  static {
    Arrays.fill(KEY, (byte) 9);
  }

  private final WorkQueue queue =
      new InMemoryWorkQueue(
          IntakeQueue.policies(
              5, Duration.ofSeconds(30), Duration.ofMillis(100), Duration.ofSeconds(1)),
          Clock.systemUTC());
  private final AesGcmPayloadCipher cipher = new AesGcmPayloadCipher(KEY);
  private final DurableRunIntakePublisher publisher =
      new DurableRunIntakePublisher(queue, cipher, new IntakeEnvelopeCodec());

  @Test
  void aPublishedBundleIsOnTheQueueForItsRepository() throws Exception {
    publisher.publish(IntakeEnvelopes.forRun("run-1", "acme/a")).toCompletableFuture().get();

    List<QueuedItem> items = queue.claim(IntakeQueue.KIND, "w", 10);

    assertThat(items).singleElement().satisfies(i -> assertThat(i.key()).isEqualTo("acme/a"));
  }

  @Test
  void whatIsStoredIsEncryptedAndNeverContainsTheBundle() throws Exception {
    IntakeEnvelope envelope =
        IntakeEnvelopes.forBundle(
            "{\"schemaVersion\":\"1.0\",\"run\":{\"id\":\"r9\",\"repository\":\"acme/a\"},"
                + "\"author\":\"alice@example.com\"}");

    publisher.publish(envelope).toCompletableFuture().get();

    byte[] stored = queue.claim(IntakeQueue.KIND, "w", 10).get(0).payload();
    assertThat(new String(stored, StandardCharsets.ISO_8859_1))
        .doesNotContain("alice")
        .doesNotContain("example.com")
        .doesNotContain("acme/a");
  }

  @Test
  void whatIsStoredOpensToTheEnvelopeUnderItsOwnRepositoryOnly() throws Exception {
    IntakeEnvelope envelope = IntakeEnvelopes.forRun("run-1", "acme/a");
    publisher.publish(envelope).toCompletableFuture().get();
    byte[] stored = queue.claim(IntakeQueue.KIND, "w", 10).get(0).payload();

    String json =
        new String(cipher.open(stored, IntakeQueue.context("acme/a")), StandardCharsets.UTF_8);

    assertThat(new IntakeEnvelopeCodec().decode(json)).isEqualTo(envelope);
    assertThatThrownBy(() -> cipher.open(stored, IntakeQueue.context("acme/other")))
        .isInstanceOf(RuntimeException.class);
  }

  /** A queue that cannot be reached. */
  private static WorkQueue unreachable() {
    return new WorkQueue() {
      private <T> T down() {
        throw new IllegalStateException("database unreachable");
      }

      @Override
      public EnqueueResult enqueue(String k, String key, String d, byte[] p, Instant nb) {
        return down();
      }

      @Override
      public List<QueuedItem> claim(String k, String w, int max) {
        return down();
      }

      @Override
      public boolean complete(long id, String w) {
        return down();
      }

      @Override
      public RetryOutcome retry(long id, String w, String e) {
        return down();
      }

      @Override
      public boolean hold(long id, String w, String r) {
        return down();
      }

      @Override
      public boolean defer(long id, String w, Instant nb) {
        return down();
      }

      @Override
      public int countStarted(String k, String key, Instant since, long exclude) {
        return down();
      }

      @Override
      public boolean deadLetter(long id, String w, String e) {
        return down();
      }

      @Override
      public int purge(Instant a, Instant b) {
        return down();
      }

      @Override
      public Optional<QueueEntry> find(long id) {
        return down();
      }

      @Override
      public QueueCounts counts(String kind) {
        return down();
      }
    };
  }

  @Test
  void anUnreachableDatabaseIsReportedAsTheQueueBeingUnavailable() {
    DurableRunIntakePublisher broken =
        new DurableRunIntakePublisher(unreachable(), cipher, new IntakeEnvelopeCodec());

    assertThatThrownBy(
            () -> broken.publish(IntakeEnvelopes.forRun("r")).toCompletableFuture().get())
        .isInstanceOf(ExecutionException.class)
        .hasCauseInstanceOf(QueueUnavailableException.class);
  }

  @Test
  void aBundleIsNeverReportedAcceptedWhenItWasNotStored() {
    DurableRunIntakePublisher broken =
        new DurableRunIntakePublisher(unreachable(), cipher, new IntakeEnvelopeCodec());

    assertThat(broken.publish(IntakeEnvelopes.forRun("r")).toCompletableFuture())
        .isCompletedExceptionally();
  }
}
