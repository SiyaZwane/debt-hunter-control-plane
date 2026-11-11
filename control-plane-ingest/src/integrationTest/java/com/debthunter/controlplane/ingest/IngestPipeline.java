package com.debthunter.controlplane.ingest;

import static org.awaitility.Awaitility.await;

import com.debthunter.controlplane.ingest.intake.IntakeEnvelopes;
import com.debthunter.controlplane.ingest.intake.KafkaIntakeListener;
import com.debthunter.controlplane.ingest.intake.KafkaRunIntakeQueuePublisher;
import com.debthunter.controlplane.ingest.lifecycle.InMemoryCommitGraph;
import com.debthunter.controlplane.ingest.lifecycle.LifecycleSink;
import com.debthunter.controlplane.ingest.normalise.RunNormaliser;
import com.debthunter.controlplane.ingest.pseudonymise.CompositeRunSink;
import com.debthunter.controlplane.ingest.pseudonymise.FindingStoreSink;
import com.debthunter.controlplane.ingest.pseudonymise.IdentityPseudonymiser;
import com.debthunter.controlplane.ingest.pseudonymise.PseudonymKeyStore;
import com.debthunter.controlplane.ingest.pseudonymise.PseudonymisingSink;
import com.debthunter.controlplane.ingest.pseudonymise.RunBundlePseudonymiser;
import com.debthunter.controlplane.ingest.receive.InMemoryRunBundleDeduplicator;
import com.debthunter.controlplane.ingest.receive.NormalisingBundleHandler;
import com.debthunter.controlplane.ingest.receive.RunBundleReceiver;
import com.debthunter.controlplane.store.findings.InMemoryPseudonymisedFindingStore;
import com.debthunter.controlplane.store.findings.JdbcPseudonymisedFindingStore;
import com.debthunter.controlplane.store.findings.PseudonymisedFindingStore;
import com.debthunter.controlplane.store.identity.InMemoryPseudonymDirectory;
import com.debthunter.controlplane.store.lifecycle.FindingStateRepository;
import com.debthunter.controlplane.store.lifecycle.InMemoryFindingStateRepository;
import com.debthunter.controlplane.store.lifecycle.InMemoryTransitionLogRepository;
import com.debthunter.controlplane.store.lifecycle.JdbcFindingStateRepository;
import com.debthunter.controlplane.store.lifecycle.JdbcTransitionLogRepository;
import com.debthunter.controlplane.store.lifecycle.TransitionLogRepository;
import com.debthunter.controlplane.store.runs.InMemoryRunRepository;
import com.debthunter.controlplane.store.runs.JdbcRunRepository;
import com.debthunter.controlplane.store.runs.RunRepository;
import com.debthunter.controlplane.store.suppressions.InMemorySuppressionLookup;
import com.debthunter.controlplane.testkit.KafkaTestBroker;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/**
 * The whole ingest path from the queue to the stores: a real Kafka broker, the listener, the
 * de-duplicating receiver, normalisation, the pseudonymising gate, and the finding store.
 */
final class IngestPipeline implements AutoCloseable {

  /** Everything the pipeline writes to, so a second pipeline can share it (a "restart"). */
  static final class Stores {
    final PseudonymisedFindingStore findings;
    final InMemoryPseudonymDirectory directory = new InMemoryPseudonymDirectory();
    final RunRepository runs;
    final FindingStateRepository states;
    final TransitionLogRepository transitions;
    final InMemoryCommitGraph commits = new InMemoryCommitGraph();
    final InMemorySuppressionLookup suppressions = new InMemorySuppressionLookup();

    /** In-memory stores. */
    Stores() {
      findings = new InMemoryPseudonymisedFindingStore();
      runs = new InMemoryRunRepository();
      states = new InMemoryFindingStateRepository();
      transitions = new InMemoryTransitionLogRepository();
    }

    /** Durable stores over PostgreSQL. The pseudonym directory is deliberately not durable here. */
    Stores(DataSource dataSource) {
      findings = new JdbcPseudonymisedFindingStore(dataSource);
      runs = new JdbcRunRepository(dataSource);
      states = new JdbcFindingStateRepository(dataSource);
      transitions = new JdbcTransitionLogRepository(dataSource);
    }
  }

  final Stores stores;
  final PseudonymisedFindingStore findings;
  final InMemoryPseudonymDirectory directory;
  final RunRepository runs;
  final FindingStateRepository states;
  final TransitionLogRepository transitions;
  final InMemoryCommitGraph commits;
  final InMemorySuppressionLookup suppressions;
  final List<String> quarantinedRunIds = new CopyOnWriteArrayList<>();

  private final KafkaRunIntakeQueuePublisher publisher;
  private final KafkaIntakeListener listener;

  IngestPipeline(PseudonymKeyStore keyStore) throws Exception {
    this(keyStore, new Stores(), null);
  }

  IngestPipeline(PseudonymKeyStore keyStore, Stores stores) throws Exception {
    this(keyStore, stores, null);
  }

  /**
   * @param transitionLog the log to write to instead of the stores' own, to inject failures
   */
  IngestPipeline(PseudonymKeyStore keyStore, Stores stores, TransitionLogRepository transitionLog)
      throws Exception {
    this.stores = stores;
    this.findings = stores.findings;
    this.directory = stores.directory;
    this.runs = stores.runs;
    this.states = stores.states;
    this.transitions = stores.transitions;
    this.commits = stores.commits;
    this.suppressions = stores.suppressions;
    TransitionLogRepository log = transitionLog == null ? stores.transitions : transitionLog;
    String bootstrap = KafkaTestBroker.bootstrapServers();
    String topic = KafkaTopics.create(bootstrap);
    RunBundleReceiver receiver =
        new RunBundleReceiver(
            new InMemoryRunBundleDeduplicator(100),
            new NormalisingBundleHandler(
                new RunNormaliser(),
                new PseudonymisingSink(
                    new RunBundlePseudonymiser(new IdentityPseudonymiser(keyStore, directory)),
                    new CompositeRunSink(
                        List.of(
                            new FindingStoreSink(findings),
                            new LifecycleSink(runs, states, log, commits, suppressions)))),
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

  /** Waits until a run has been recorded as applied (in any status). */
  void awaitRecorded(String runId) {
    await().atMost(Duration.ofSeconds(60)).until(() -> runs.find(runId).isPresent());
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
