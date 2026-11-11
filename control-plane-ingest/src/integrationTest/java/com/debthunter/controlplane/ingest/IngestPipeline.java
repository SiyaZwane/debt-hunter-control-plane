package com.debthunter.controlplane.ingest;

import static org.awaitility.Awaitility.await;

import com.debthunter.controlplane.ingest.intake.IntakeEnvelopes;
import com.debthunter.controlplane.ingest.intake.KafkaIntakeListener;
import com.debthunter.controlplane.ingest.intake.KafkaRunIntakeQueuePublisher;
import com.debthunter.controlplane.ingest.normalise.RunNormaliser;
import com.debthunter.controlplane.ingest.pseudonymise.FindingStoreSink;
import com.debthunter.controlplane.ingest.pseudonymise.IdentityPseudonymiser;
import com.debthunter.controlplane.ingest.pseudonymise.PseudonymKeyStore;
import com.debthunter.controlplane.ingest.pseudonymise.PseudonymisingSink;
import com.debthunter.controlplane.ingest.pseudonymise.RunBundlePseudonymiser;
import com.debthunter.controlplane.ingest.receive.InMemoryRunBundleDeduplicator;
import com.debthunter.controlplane.ingest.receive.NormalisingBundleHandler;
import com.debthunter.controlplane.ingest.receive.RunBundleReceiver;
import com.debthunter.controlplane.store.findings.InMemoryPseudonymisedFindingStore;
import com.debthunter.controlplane.store.identity.InMemoryPseudonymDirectory;
import com.debthunter.controlplane.testkit.KafkaTestBroker;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

/**
 * The whole ingest path from the queue to the stores: a real Kafka broker, the listener, the
 * de-duplicating receiver, normalisation, the pseudonymising gate, and the finding store.
 */
final class IngestPipeline implements AutoCloseable {

  final InMemoryPseudonymisedFindingStore findings = new InMemoryPseudonymisedFindingStore();
  final InMemoryPseudonymDirectory directory = new InMemoryPseudonymDirectory();
  final List<String> quarantinedRunIds = new CopyOnWriteArrayList<>();

  private final KafkaRunIntakeQueuePublisher publisher;
  private final KafkaIntakeListener listener;

  IngestPipeline(PseudonymKeyStore keyStore) throws Exception {
    String bootstrap = KafkaTestBroker.bootstrapServers();
    String topic = KafkaTopics.create(bootstrap);
    RunBundleReceiver receiver =
        new RunBundleReceiver(
            new InMemoryRunBundleDeduplicator(100),
            new NormalisingBundleHandler(
                new RunNormaliser(),
                new PseudonymisingSink(
                    new RunBundlePseudonymiser(new IdentityPseudonymiser(keyStore, directory)),
                    new FindingStoreSink(findings)),
                (envelope, reason) -> quarantinedRunIds.add(envelope.runId())));
    publisher = KafkaRunIntakeQueuePublisher.connect(bootstrap, topic, Duration.ofSeconds(10));
    listener =
        KafkaIntakeListener.connect(bootstrap, "group-" + UUID.randomUUID(), topic, receiver);
    listener.start();
  }

  /** Publishes a bundle to the queue and returns without waiting for it to be processed. */
  void publish(String bundleJson) throws Exception {
    publisher
        .publish(IntakeEnvelopes.forBundle(bundleJson))
        .toCompletableFuture()
        .get(30, TimeUnit.SECONDS);
  }

  /** Waits until a run's findings are in the store. */
  void awaitStored(String runId) {
    await().atMost(Duration.ofSeconds(60)).until(() -> !findings.findingsForRun(runId).isEmpty());
  }

  @Override
  public void close() {
    listener.close();
    publisher.close();
  }
}
