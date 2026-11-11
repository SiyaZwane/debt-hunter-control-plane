package com.debthunter.controlplane.governance;

import com.debthunter.controlplane.ingest.intake.IntakeEnvelope;
import com.debthunter.controlplane.ingest.lifecycle.LifecycleSink;
import com.debthunter.controlplane.ingest.normalise.RunNormaliser;
import com.debthunter.controlplane.ingest.pseudonymise.FindingStoreSink;
import com.debthunter.controlplane.ingest.pseudonymise.IdentityPseudonymiser;
import com.debthunter.controlplane.ingest.pseudonymise.InMemoryPseudonymKeyStore;
import com.debthunter.controlplane.ingest.pseudonymise.RunBundlePseudonymiser;
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
import com.debthunter.controlplane.testkit.RunBundleBuilder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Pushes real bundles through the real normaliser, pseudonymiser, finding store and lifecycle, with
 * no queue in between, so tests get real transitions and real runs to project from.
 */
public final class PipelineDriver {

  private static final ObjectMapper JSON = new ObjectMapper();

  public final RunRepository runs;
  public final FindingStateRepository states;
  public final TransitionLogRepository transitions;
  public final PseudonymisedFindingStore findings;

  private final RunNormaliser normaliser = new RunNormaliser();
  private final RunBundlePseudonymiser pseudonymiser =
      new RunBundlePseudonymiser(
          new IdentityPseudonymiser(
              new InMemoryPseudonymKeyStore(new byte[32]), new InMemoryPseudonymDirectory()));
  private final FindingStoreSink findingSink;
  private final LifecycleSink lifecycleSink;

  private PipelineDriver(
      RunRepository runs,
      FindingStateRepository states,
      TransitionLogRepository transitions,
      PseudonymisedFindingStore findings) {
    this.runs = runs;
    this.states = states;
    this.transitions = transitions;
    this.findings = findings;
    this.findingSink = new FindingStoreSink(findings);
    this.lifecycleSink =
        new LifecycleSink(
            runs,
            states,
            transitions,
            (repo, commit) -> java.util.Optional.empty(),
            new InMemorySuppressionLookup());
  }

  public static PipelineDriver inMemory() {
    return new PipelineDriver(
        new InMemoryRunRepository(),
        new InMemoryFindingStateRepository(),
        new InMemoryTransitionLogRepository(),
        new InMemoryPseudonymisedFindingStore());
  }

  public static PipelineDriver postgres(DataSource dataSource) {
    return new PipelineDriver(
        new JdbcRunRepository(dataSource),
        new JdbcFindingStateRepository(dataSource),
        new JdbcTransitionLogRepository(dataSource),
        new JdbcPseudonymisedFindingStore(dataSource));
  }

  /** Applies one run as the pipeline would. */
  public void apply(RunBundleBuilder bundle) {
    String json = bundle.build();
    JsonNode root = readTree(json);
    IntakeEnvelope envelope =
        new IntakeEnvelope(
            UUID.randomUUID(),
            "svc",
            root.at("/run/repository").asText(),
            "1.0",
            Instant.parse(root.at("/run/timestamp").asText()),
            root.at("/run/id").asText(),
            json);
    var pseudonymised = pseudonymiser.pseudonymise(normaliser.normalise(envelope));
    findingSink.accept(pseudonymised);
    lifecycleSink.accept(pseudonymised);
  }

  private static JsonNode readTree(String json) {
    try {
      return JSON.readTree(json);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }
}
