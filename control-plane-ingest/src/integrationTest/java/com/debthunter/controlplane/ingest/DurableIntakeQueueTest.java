package com.debthunter.controlplane.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.debthunter.controlplane.ingest.intake.DurableIntakeWorker;
import com.debthunter.controlplane.ingest.intake.DurableRunIntakePublisher;
import com.debthunter.controlplane.ingest.intake.IntakeEnvelope;
import com.debthunter.controlplane.ingest.intake.IntakeEnvelopeCodec;
import com.debthunter.controlplane.ingest.intake.IntakeEnvelopes;
import com.debthunter.controlplane.ingest.intake.IntakeQueue;
import com.debthunter.controlplane.ingest.receive.Handlers;
import com.debthunter.controlplane.ingest.receive.InMemoryRunBundleDeduplicator;
import com.debthunter.controlplane.ingest.receive.RunBundleHandler;
import com.debthunter.controlplane.ingest.receive.RunBundleReceiver;
import com.debthunter.controlplane.store.jdbc.Jdbc;
import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.store.queue.JdbcWorkQueue;
import com.debthunter.controlplane.store.queue.QueueEntry;
import com.debthunter.controlplane.store.queue.WorkQueue;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The intake queue on a real database: bundles survive the process, a lost worker's bundle goes to
 * another, a poison bundle does not block its repository for ever, and nothing readable is left in
 * the table.
 */
@Tag("integration")
class DurableIntakeQueueTest {

  private final DataSource dataSource = PostgresTestDatabase.freshSchema();
  private final List<String> processed = new CopyOnWriteArrayList<>();

  private WorkQueue queue() {
    StoreMigrations.migrate(dataSource);
    return IntakeTestQueue.over(dataSource);
  }

  private static RunBundleReceiver receiver(RunBundleHandler handler) {
    return new RunBundleReceiver(new InMemoryRunBundleDeduplicator(100), handler);
  }

  private static void publish(WorkQueue queue, IntakeEnvelope envelope) throws Exception {
    IntakeTestQueue.publisher(queue).publish(envelope).toCompletableFuture().get();
  }

  @Test
  void bundlesAcceptedWhileNothingWasListeningAreProcessedWhenAWorkerAppears() throws Exception {
    WorkQueue queue = queue();
    publish(queue, IntakeEnvelopes.forRun("r1", "acme/a"));
    publish(queue, IntakeEnvelopes.forRun("r2", "acme/a"));

    // A different process, started afterwards, with only the database in common.
    WorkQueue otherProcess = IntakeTestQueue.over(dataSource);
    try (DurableIntakeWorker worker =
        IntakeTestQueue.startedWorker(
            otherProcess, receiver(Handlers.processing(e -> processed.add(e.runId()))))) {
      await().atMost(Duration.ofSeconds(30)).until(() -> processed.size() == 2);
    }

    assertThat(processed).containsExactly("r1", "r2");
  }

  @Test
  void aBundleHeldByAWorkerThatDiedIsProcessedByAnotherAfterTheLeaseExpires() throws Exception {
    StoreMigrations.migrate(dataSource);
    WorkQueue shortLease =
        new JdbcWorkQueue(
            dataSource,
            IntakeQueue.policies(
                5, Duration.ofMillis(300), Duration.ofMillis(50), Duration.ofMillis(200)),
            Clock.systemUTC());
    publish(shortLease, IntakeEnvelopes.forRun("r1", "acme/a"));
    shortLease.claim(IntakeQueue.KIND, "worker-that-died", 10);

    try (DurableIntakeWorker worker =
        IntakeTestQueue.startedWorker(
            shortLease, receiver(Handlers.processing(e -> processed.add(e.runId()))))) {
      await().atMost(Duration.ofSeconds(30)).until(() -> !processed.isEmpty());
    }

    assertThat(processed).containsExactly("r1");
  }

  @Test
  void aPoisonBundleIsSetAsideAfterItsAttemptsAndItsRepositoryCarriesOn() throws Exception {
    WorkQueue queue = queue();
    publish(queue, IntakeEnvelopes.forRun("poison", "acme/a"));
    publish(queue, IntakeEnvelopes.forRun("fine", "acme/a"));

    try (DurableIntakeWorker worker =
        IntakeTestQueue.startedWorker(
            queue,
            receiver(
                Handlers.processing(
                    e -> {
                      if (e.runId().equals("poison")) {
                        throw new IllegalStateException("cannot ever work");
                      }
                      processed.add(e.runId());
                    })))) {
      await().atMost(Duration.ofSeconds(30)).until(() -> processed.contains("fine"));
    }

    assertThat(queue.counts(IntakeQueue.KIND).dead()).isEqualTo(1);
    assertThat(queue.find(1).orElseThrow().status()).isEqualTo(QueueEntry.Status.DEAD);
    assertThat(queue.find(1).orElseThrow().attempts()).isEqualTo(5);
  }

  @Test
  void nothingReadableIsInTheTableAtAnyPointAndFinishedItemsHoldNoPayloadAtAll() throws Exception {
    WorkQueue queue = queue();
    IntakeEnvelope envelope =
        IntakeEnvelopes.forBundle(
            "{\"schemaVersion\":\"1.0\",\"run\":{\"id\":\"r9\",\"repository\":\"acme/a\"},"
                + "\"authors\":[\"alice@example.com\",\"Bob Builder\"]}");
    publish(queue, envelope);

    String waiting = payloadAsText();
    assertThat(waiting)
        .as("while waiting")
        .isNotEmpty()
        .doesNotContain("alice")
        .doesNotContain("Bob");

    try (DurableIntakeWorker worker =
        IntakeTestQueue.startedWorker(
            queue, receiver(Handlers.processing(e -> processed.add(e.runId()))))) {
      await().atMost(Duration.ofSeconds(30)).until(() -> !processed.isEmpty());
    }
    await().atMost(Duration.ofSeconds(10)).until(() -> queue.counts(IntakeQueue.KIND).done() == 1);

    assertThat(payloadAsText()).as("once finished").isEmpty();
  }

  @Test
  void aBundleSetAsideAsUnreadableKeepsOnlyItsEncryptedFormForInvestigation() throws Exception {
    WorkQueue queue = queue();
    new DurableRunIntakePublisher(
            queue,
            new com.debthunter.controlplane.store.queue.AesGcmPayloadCipher(otherKey()),
            new IntakeEnvelopeCodec())
        .publish(IntakeEnvelopes.forRun("r1", "acme/a"))
        .toCompletableFuture()
        .get();

    try (DurableIntakeWorker worker =
        IntakeTestQueue.startedWorker(
            queue, receiver(Handlers.processing(e -> processed.add(e.runId()))))) {
      await()
          .atMost(Duration.ofSeconds(30))
          .until(() -> queue.counts(IntakeQueue.KIND).dead() == 1);
    }

    assertThat(processed).isEmpty();
    assertThat(payloadAsText()).isNotEmpty().doesNotContain("acme/a").doesNotContain("r1");
  }

  private static byte[] otherKey() {
    byte[] k = new byte[32];
    java.util.Arrays.fill(k, (byte) 1);
    return k;
  }

  private String payloadAsText() {
    return new Jdbc(dataSource)
            .query(
                "SELECT payload FROM work_queue ORDER BY id",
                row -> new String(row.getBytes("payload"), StandardCharsets.ISO_8859_1))
            .stream()
            .reduce("", String::concat);
  }
}
