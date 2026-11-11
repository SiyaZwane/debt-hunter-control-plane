package com.debthunter.controlplane;

import com.debthunter.controlplane.ingest.lifecycle.CommitGraph;
import com.debthunter.controlplane.ingest.lifecycle.LifecycleSink;
import com.debthunter.controlplane.ingest.normalise.RunNormaliser;
import com.debthunter.controlplane.ingest.pseudonymise.CompositeRunSink;
import com.debthunter.controlplane.ingest.pseudonymise.EvidenceSink;
import com.debthunter.controlplane.ingest.pseudonymise.FindingStoreSink;
import com.debthunter.controlplane.ingest.pseudonymise.IdentityPseudonymiser;
import com.debthunter.controlplane.ingest.pseudonymise.InMemoryPseudonymKeyStore;
import com.debthunter.controlplane.ingest.pseudonymise.PseudonymKeyStore;
import com.debthunter.controlplane.ingest.pseudonymise.PseudonymisedRunSink;
import com.debthunter.controlplane.ingest.pseudonymise.PseudonymisingSink;
import com.debthunter.controlplane.ingest.pseudonymise.RunBundlePseudonymiser;
import com.debthunter.controlplane.ingest.receive.InMemoryRunBundleDeduplicator;
import com.debthunter.controlplane.ingest.receive.NormalisingBundleHandler;
import com.debthunter.controlplane.ingest.receive.QuarantineSink;
import com.debthunter.controlplane.ingest.receive.RunBundleHandler;
import com.debthunter.controlplane.ingest.receive.RunBundleReceiver;
import com.debthunter.controlplane.ingest.receive.StoringQuarantineSink;
import com.debthunter.controlplane.store.evidence.EvidenceStorageService;
import com.debthunter.controlplane.store.findings.PseudonymisedFindingStore;
import com.debthunter.controlplane.store.identity.InMemoryPseudonymDirectory;
import com.debthunter.controlplane.store.identity.PseudonymDirectory;
import com.debthunter.controlplane.store.lifecycle.FindingStateRepository;
import com.debthunter.controlplane.store.lifecycle.TransitionLogRepository;
import com.debthunter.controlplane.store.quarantine.QuarantineRepository;
import com.debthunter.controlplane.store.runs.RunRepository;
import com.debthunter.controlplane.store.suppressions.SuppressionLookup;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles the ingest pipeline that runs behind the queue:
 *
 * <pre>
 * queue → receiver → normalise → pseudonymise → [ findings | lifecycle | evidence ]
 *                        └─ can never be normalised → quarantine
 * </pre>
 *
 * <p>The order is the design: nothing reaches a store except through the pseudonymiser, and a
 * failure anywhere holds the bundle for retry rather than losing it.
 */
@Configuration(proxyBeanMethods = false)
public class IngestPipelineConfiguration {

  /** How many recent run ids the receiver remembers, as a fast path in front of the run store. */
  private static final int RECENT_RUN_IDS = 10_000;

  /**
   * The pseudonymisation key source. Refuses to start without a usable key: nothing may be
   * processed unpseudonymised, and there is no fallback.
   *
   * <p>This reads the key from configuration, which is the interim source. The Azure Key Vault
   * binding replaces it; {@code KeyVaultPseudonymKeyStore} is already written against a port for
   * it.
   *
   * @param properties the control-plane configuration
   * @return the key store
   */
  @Bean
  public PseudonymKeyStore pseudonymKeyStore(ControlPlaneProperties properties) {
    String key = properties.pseudonym().key();
    if (key == null || key.isBlank()) {
      throw new IllegalStateException(
          "The pseudonymisation key is not configured. Set debthunter.controlplane.pseudonym.key"
              + " (environment: CONTROL_PLANE_PSEUDONYM_KEY), base64-encoded, at least 32 bytes.");
    }
    try {
      return new InMemoryPseudonymKeyStore(Base64.getDecoder().decode(key.strip()));
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "debthunter.controlplane.pseudonym.key must be base64-encoded and at least 32 bytes");
    }
  }

  /**
   * Which identity each pseudonym stands for. <strong>In memory only</strong>: it is lost on
   * restart, so re-identification works only for contributors seen since. A durable, encrypted,
   * access-restricted directory replaces it.
   *
   * @return the directory
   */
  @Bean
  public PseudonymDirectory pseudonymDirectory() {
    return new InMemoryPseudonymDirectory();
  }

  /**
   * The commit graph. <strong>There is no source for it yet</strong>: a bundle carries its commit
   * id but not its parents or commit date. Every commit is therefore unknown, so runs apply in
   * arrival order and the run's own timestamp stands in for the commit date.
   *
   * @return a graph that knows nothing
   */
  @Bean
  public CommitGraph commitGraph() {
    return (repository, commit) -> Optional.empty();
  }

  /**
   * The pseudonymiser for the whole pipeline.
   *
   * @param keys where the key comes from
   * @param directory where identities are remembered
   * @return the pseudonymiser
   */
  @Bean
  public RunBundlePseudonymiser runBundlePseudonymiser(
      PseudonymKeyStore keys, PseudonymDirectory directory) {
    return new RunBundlePseudonymiser(new IdentityPseudonymiser(keys, directory));
  }

  /**
   * Everything that happens to a bundle once it is safe to persist.
   *
   * @param findings the finding store
   * @param runs the run store
   * @param states current finding states
   * @param transitions the transition log
   * @param commits the commit graph
   * @param suppressions the suppression register
   * @param evidence evidence storage
   * @return the stages, in order
   */
  @Bean
  public PseudonymisedRunSink pseudonymisedRunSink(
      PseudonymisedFindingStore findings,
      RunRepository runs,
      FindingStateRepository states,
      TransitionLogRepository transitions,
      CommitGraph commits,
      SuppressionLookup suppressions,
      EvidenceStorageService evidence) {
    return new CompositeRunSink(
        List.of(
            new FindingStoreSink(findings),
            new EvidenceSink(evidence),
            new LifecycleSink(runs, states, transitions, commits, suppressions)));
  }

  /**
   * Where bundles that can never be processed are recorded.
   *
   * @param quarantine the quarantine store
   * @param clock the clock
   * @return the sink
   */
  @Bean
  public QuarantineSink quarantineSink(QuarantineRepository quarantine, Clock clock) {
    return new StoringQuarantineSink(quarantine, clock);
  }

  /**
   * The first stage after the queue.
   *
   * @param pseudonymiser pseudonymises bundles
   * @param next the stages after pseudonymisation
   * @param quarantine where unprocessable bundles go
   * @return the handler
   */
  @Bean
  public RunBundleHandler runBundleHandler(
      RunBundlePseudonymiser pseudonymiser, PseudonymisedRunSink next, QuarantineSink quarantine) {
    return new NormalisingBundleHandler(
        new RunNormaliser(), new PseudonymisingSink(pseudonymiser, next), quarantine);
  }

  /**
   * Takes bundles off the queue and dispatches each exactly once.
   *
   * @param handler the first stage
   * @return the receiver
   */
  @Bean
  public RunBundleReceiver runBundleReceiver(RunBundleHandler handler) {
    return new RunBundleReceiver(new InMemoryRunBundleDeduplicator(RECENT_RUN_IDS), handler);
  }
}
