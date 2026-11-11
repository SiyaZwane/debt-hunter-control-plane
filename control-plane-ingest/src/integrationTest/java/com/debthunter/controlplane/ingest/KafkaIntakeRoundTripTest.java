package com.debthunter.controlplane.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.debthunter.controlplane.ingest.intake.IntakeEnvelope;
import com.debthunter.controlplane.ingest.intake.IntakeEnvelopes;
import com.debthunter.controlplane.ingest.intake.KafkaIntakeListener;
import com.debthunter.controlplane.ingest.intake.KafkaRunIntakeQueuePublisher;
import com.debthunter.controlplane.ingest.receive.Handlers;
import com.debthunter.controlplane.ingest.receive.InMemoryRunBundleDeduplicator;
import com.debthunter.controlplane.ingest.receive.RunBundleHandler;
import com.debthunter.controlplane.ingest.receive.RunBundleReceiver;
import com.debthunter.controlplane.testkit.KafkaTestBroker;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Exercises the intake publisher and listener against a real Kafka broker. */
@Tag("integration")
class KafkaIntakeRoundTripTest {

  private static final Duration PUBLISH_WAIT = Duration.ofSeconds(10);
  private static final Duration DELIVERY_WAIT = Duration.ofSeconds(60);

  @Test
  void aPublishedBundleIsDeliveredToTheReceiverWithItsEnvelopeIntact() throws Exception {
    String bootstrap = KafkaTestBroker.bootstrapServers();
    String topic = KafkaTopics.create(bootstrap);
    List<IntakeEnvelope> handled = new CopyOnWriteArrayList<>();
    RunBundleReceiver receiver =
        new RunBundleReceiver(
            new InMemoryRunBundleDeduplicator(100), Handlers.processing(handled::add));

    try (KafkaRunIntakeQueuePublisher publisher =
            KafkaRunIntakeQueuePublisher.connect(bootstrap, topic, PUBLISH_WAIT);
        KafkaIntakeListener listener =
            KafkaIntakeListener.connect(bootstrap, "group-" + UUID.randomUUID(), topic, receiver)) {
      listener.start();
      IntakeEnvelope envelope = IntakeEnvelopes.forRun("run-1");

      publisher.publish(envelope).toCompletableFuture().get(30, TimeUnit.SECONDS);

      await()
          .atMost(DELIVERY_WAIT)
          .untilAsserted(() -> assertThat(handled).containsExactly(envelope));
    }
  }

  @Test
  void aHandlerFailureHoldsTheBundleAndRetriesItWithoutLosingOrReorderingAnything()
      throws Exception {
    String bootstrap = KafkaTestBroker.bootstrapServers();
    String topic = KafkaTopics.create(bootstrap);
    AtomicInteger attemptsAtFirstRun = new AtomicInteger();
    List<String> succeeded = new CopyOnWriteArrayList<>();
    RunBundleHandler flaky =
        envelope -> {
          if (envelope.runId().equals("run-1") && attemptsAtFirstRun.incrementAndGet() <= 2) {
            throw new IllegalStateException("transient downstream failure");
          }
          succeeded.add(envelope.runId());
          return RunBundleHandler.Disposition.PROCESSED;
        };
    RunBundleReceiver receiver =
        new RunBundleReceiver(new InMemoryRunBundleDeduplicator(100), flaky);

    try (KafkaRunIntakeQueuePublisher publisher =
            KafkaRunIntakeQueuePublisher.connect(bootstrap, topic, PUBLISH_WAIT);
        KafkaIntakeListener listener =
            KafkaIntakeListener.connect(bootstrap, "group-" + UUID.randomUUID(), topic, receiver)) {
      listener.start();
      publisher
          .publish(IntakeEnvelopes.forRun("run-1"))
          .toCompletableFuture()
          .get(30, TimeUnit.SECONDS);
      publisher
          .publish(IntakeEnvelopes.forRun("run-2"))
          .toCompletableFuture()
          .get(30, TimeUnit.SECONDS);

      await()
          .atMost(DELIVERY_WAIT)
          .untilAsserted(() -> assertThat(succeeded).containsExactly("run-1", "run-2"));
      assertThat(attemptsAtFirstRun).hasValue(3);
    }
  }

  @Test
  void anUndecodableRecordIsSkippedAndDoesNotBlockLaterBundles() throws Exception {
    String bootstrap = KafkaTestBroker.bootstrapServers();
    String topic = KafkaTopics.create(bootstrap);
    List<String> handled = new CopyOnWriteArrayList<>();
    RunBundleReceiver receiver =
        new RunBundleReceiver(
            new InMemoryRunBundleDeduplicator(100),
            Handlers.processing(envelope -> handled.add(envelope.runId())));

    Properties raw = new Properties();
    raw.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
    raw.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    raw.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
    try (KafkaProducer<String, String> garbageProducer = new KafkaProducer<>(raw);
        KafkaRunIntakeQueuePublisher publisher =
            KafkaRunIntakeQueuePublisher.connect(bootstrap, topic, PUBLISH_WAIT);
        KafkaIntakeListener listener =
            KafkaIntakeListener.connect(bootstrap, "group-" + UUID.randomUUID(), topic, receiver)) {
      garbageProducer
          .send(new ProducerRecord<>(topic, "acme/widgets", "{ definitely not an envelope"))
          .get(30, TimeUnit.SECONDS);
      listener.start();
      publisher
          .publish(IntakeEnvelopes.forRun("run-after"))
          .toCompletableFuture()
          .get(30, TimeUnit.SECONDS);

      await()
          .atMost(DELIVERY_WAIT)
          .untilAsserted(() -> assertThat(handled).containsExactly("run-after"));
    }
  }
}
