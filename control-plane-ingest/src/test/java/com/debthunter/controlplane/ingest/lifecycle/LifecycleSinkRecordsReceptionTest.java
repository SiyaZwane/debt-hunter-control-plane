package com.debthunter.controlplane.ingest.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.lifecycle.InMemoryFindingStateRepository;
import com.debthunter.controlplane.store.lifecycle.InMemoryTransitionLogRepository;
import com.debthunter.controlplane.store.runs.InMemoryRunRepository;
import com.debthunter.controlplane.store.suppressions.InMemorySuppressionLookup;
import com.debthunter.controlplane.testkit.RunBundleBuilder;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Coverage tracking judges silence by when a run was received, so the run store must keep that. */
class LifecycleSinkRecordsReceptionTest {

  private final InMemoryRunRepository runs = new InMemoryRunRepository();
  private final LifecycleSink sink =
      new LifecycleSink(
          runs,
          new InMemoryFindingStateRepository(),
          new InMemoryTransitionLogRepository(),
          (repo, commit) -> java.util.Optional.empty(),
          new InMemorySuppressionLookup());

  @Test
  void aRunIsStoredWithWhenItWasReceivedAndHowMuchHistoryItHad() {
    sink.accept(
        LifecycleFixtures.bundle(
            RunBundleBuilder.run().id("r1").repository("acme/widgets").historyDepth("SHALLOW")));

    assertThat(runs.find("r1").get().historyDepth()).isEqualTo("SHALLOW");
    // The test envelopes are all received at this instant.
    assertThat(runs.find("r1").get().receivedAt()).isEqualTo(Instant.parse("2026-01-01T00:00:00Z"));
  }

  @Test
  void aScopeLimitedRunIsStillRecordedAsReceived() {
    sink.accept(
        LifecycleFixtures.bundle(
            RunBundleBuilder.run().id("pr").repository("acme/widgets").pullRequest("c0", "9")));

    assertThat(runs.find("pr").get().receivedAt()).isNotNull();
  }

  @Test
  void aRunIsStoredWithThePolicyBundleVersionItWasAssessedAgainst() {
    sink.accept(
        LifecycleFixtures.bundle(RunBundleBuilder.run().id("r1").repository("acme/widgets")));

    assertThat(runs.find("r1").get().policyBundleVersion()).isEqualTo("1.0");
  }
}
