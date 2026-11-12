package com.debthunter.controlplane.ingest;

import com.debthunter.controlplane.ingest.intake.DurableIntakeWorker;
import com.debthunter.controlplane.ingest.intake.DurableRunIntakePublisher;
import com.debthunter.controlplane.ingest.intake.IntakeEnvelopeCodec;
import com.debthunter.controlplane.ingest.intake.IntakeQueue;
import com.debthunter.controlplane.ingest.receive.RunBundleReceiver;
import com.debthunter.controlplane.store.queue.AesGcmPayloadCipher;
import com.debthunter.controlplane.store.queue.InMemoryWorkQueue;
import com.debthunter.controlplane.store.queue.JdbcWorkQueue;
import com.debthunter.controlplane.store.queue.PayloadCipher;
import com.debthunter.controlplane.store.queue.WorkQueue;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.UUID;
import javax.sql.DataSource;

/** The real intake queue, fast enough for tests: short backoffs and a short poll. */
final class IntakeTestQueue {

  static final PayloadCipher CIPHER = new AesGcmPayloadCipher(key());

  private IntakeTestQueue() {}

  private static byte[] key() {
    byte[] k = new byte[32];
    Arrays.fill(k, (byte) 42);
    return k;
  }

  static WorkQueue inMemory() {
    return new InMemoryWorkQueue(policies(), Clock.systemUTC());
  }

  static WorkQueue over(DataSource dataSource) {
    return new JdbcWorkQueue(dataSource, policies(), Clock.systemUTC());
  }

  static java.util.Map<String, com.debthunter.controlplane.store.queue.QueuePolicy> policies() {
    return IntakeQueue.policies(
        5, Duration.ofSeconds(30), Duration.ofMillis(100), Duration.ofMillis(500));
  }

  static DurableRunIntakePublisher publisher(WorkQueue queue) {
    return new DurableRunIntakePublisher(queue, CIPHER, new IntakeEnvelopeCodec());
  }

  /** A started worker feeding {@code receiver}. */
  static DurableIntakeWorker startedWorker(WorkQueue queue, RunBundleReceiver receiver) {
    DurableIntakeWorker worker =
        new DurableIntakeWorker(
            queue,
            CIPHER,
            new IntakeEnvelopeCodec(),
            receiver,
            "worker-" + UUID.randomUUID(),
            20,
            Duration.ofMillis(50));
    worker.start();
    return worker;
  }
}
