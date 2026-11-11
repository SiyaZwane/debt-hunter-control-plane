package com.debthunter.controlplane.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.debthunter.controlplane.ingest.intake.IntakeEnvelopes;
import com.debthunter.controlplane.ingest.intake.KafkaIntakeListener;
import com.debthunter.controlplane.ingest.intake.KafkaRunIntakeQueuePublisher;
import com.debthunter.controlplane.ingest.receive.Handlers;
import com.debthunter.controlplane.ingest.receive.InMemoryRunBundleDeduplicator;
import com.debthunter.controlplane.ingest.receive.RunBundleReceiver;
import com.debthunter.controlplane.testkit.KafkaTestBroker;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-BC-02: a bundle delivered more than once is processed exactly once. The intake queue is
 * at-least-once, so a pipeline retry or a broker redelivery must never produce a second run.
 */
@Tag("integration")
class CpBc02_DuplicateBundleDeduplicatedTest {

  @Test
  void cpBc02_aBundleDeliveredTwiceIsProcessedOnce() throws Exception {
    String bootstrap = KafkaTestBroker.bootstrapServers();
    String topic = KafkaTopics.create(bootstrap);
    List<String> processed = new CopyOnWriteArrayList<>();
    RunBundleReceiver receiver =
        new RunBundleReceiver(
            new InMemoryRunBundleDeduplicator(100),
            Handlers.processing(envelope -> processed.add(envelope.runId())));

    try (KafkaRunIntakeQueuePublisher publisher =
            KafkaRunIntakeQueuePublisher.connect(bootstrap, topic, Duration.ofSeconds(10));
        KafkaIntakeListener listener =
            KafkaIntakeListener.connect(bootstrap, "group-" + UUID.randomUUID(), topic, receiver)) {
      listener.start();

      // The same run, published twice (a pipeline retry), then a sentinel. All three share one
      // repository key and so one partition, which keeps their order: once the sentinel has been
      // processed, the duplicate ahead of it has certainly been consumed too.
      publisher
          .publish(IntakeEnvelopes.forRun("run-1"))
          .toCompletableFuture()
          .get(30, TimeUnit.SECONDS);
      publisher
          .publish(IntakeEnvelopes.forRun("run-1"))
          .toCompletableFuture()
          .get(30, TimeUnit.SECONDS);
      publisher
          .publish(IntakeEnvelopes.forRun("run-sentinel"))
          .toCompletableFuture()
          .get(30, TimeUnit.SECONDS);

      await()
          .atMost(Duration.ofSeconds(60))
          .untilAsserted(() -> assertThat(processed).contains("run-sentinel"));
      assertThat(processed).containsExactly("run-1", "run-sentinel");
    }
  }
}
