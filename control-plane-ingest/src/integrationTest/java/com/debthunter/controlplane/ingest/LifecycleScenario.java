package com.debthunter.controlplane.ingest;

import com.debthunter.controlplane.ingest.pseudonymise.InMemoryPseudonymKeyStore;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.lifecycle.FindingTransitionEvent;
import com.debthunter.controlplane.testkit.RunBundleBuilder;
import java.time.Instant;
import java.util.List;

/** A linear history c1 <- c2 <- ... <- c8, driven through the real queue and pipeline. */
final class LifecycleScenario implements AutoCloseable {

  static final String REPO = "acme/widgets";
  static final String SLICE = "default";

  final IngestPipeline pipeline;

  LifecycleScenario() throws Exception {
    this(new IngestPipeline.Stores());
  }

  LifecycleScenario(IngestPipeline.Stores stores) throws Exception {
    this.pipeline = new IngestPipeline(new InMemoryPseudonymKeyStore(new byte[32]), stores);
    Instant start = Instant.parse("2026-01-01T00:00:00Z");
    for (int i = 1; i <= 8; i++) {
      if (i == 1) {
        stores.commits.add(REPO, "c1", start);
      } else {
        stores.commits.add(REPO, "c" + i, start.plusSeconds(86_400L * (i - 1)), "c" + (i - 1));
      }
    }
  }

  static RunBundleBuilder run(String runId, String commit) {
    return RunBundleBuilder.run().id(runId).repository(REPO).commit(commit);
  }

  /** Publishes a run and waits until the pipeline has recorded it. */
  void apply(RunBundleBuilder bundle) throws Exception {
    String json = bundle.build();
    String runId =
        new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).at("/run/id").asText();
    pipeline.publish(json);
    pipeline.awaitRecorded(runId);
  }

  FindingState stateOf(String fingerprint) {
    var record = pipeline.states.find(REPO, SLICE, fingerprint);
    return record.map(r -> r.state()).orElse(null);
  }

  List<FindingTransitionEvent> historyOf(String fingerprint) {
    return pipeline.transitions.historyOf(REPO, SLICE, fingerprint);
  }

  @Override
  public void close() {
    pipeline.close();
  }
}
