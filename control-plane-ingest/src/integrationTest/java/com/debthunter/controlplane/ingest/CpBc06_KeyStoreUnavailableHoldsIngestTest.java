package com.debthunter.controlplane.ingest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.debthunter.controlplane.ingest.pseudonymise.KeyStoreUnavailableException;
import com.debthunter.controlplane.ingest.pseudonymise.PseudonymKey;
import com.debthunter.controlplane.testkit.RunBundleFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-BC-06 — safety-critical. If the pseudonym key store is unavailable, ingest holds the bundle:
 * it must never be processed without pseudonymisation, and it must not be lost either.
 */
@Tag("integration")
class CpBc06_KeyStoreUnavailableHoldsIngestTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static String bundleNaming(String runId, String person) throws Exception {
    ObjectNode root = (ObjectNode) JSON.readTree(RunBundleFixtures.validV1(runId, "acme/widgets"));
    ((ObjectNode) root.at("/findings/0/evidence")).putArray("authors").add(person);
    return JSON.writeValueAsString(root);
  }

  @Test
  void cpBc06_whileTheKeyStoreIsDownNothingIsStoredAndTheBundleIsHeldNotLost() throws Exception {
    AtomicBoolean vaultUp = new AtomicBoolean(false);
    AtomicInteger attempts = new AtomicInteger();
    PseudonymKey key = new PseudonymKey(new byte[32]);

    try (IngestPipeline pipeline =
        new IngestPipeline(
            () -> {
              attempts.incrementAndGet();
              if (!vaultUp.get()) {
                throw new KeyStoreUnavailableException("vault down");
              }
              return key;
            })) {
      pipeline.publish(bundleNaming("run-held", "alice@example.com"));

      // The pipeline keeps trying — it is holding, not giving up...
      await().atMost(Duration.ofSeconds(60)).until(() -> attempts.get() >= 3);
      // ...and through all of those attempts nothing has been stored or set aside.
      assertThat(pipeline.findings.findingsForRun("run-held")).isEmpty();
      assertThat(pipeline.quarantinedRunIds).isEmpty();

      // When the vault returns, the held bundle is processed — pseudonymised, and not lost.
      vaultUp.set(true);
      pipeline.awaitStored("run-held");
      var stored = pipeline.findings.findingsForRun("run-held");
      assertThat(stored.toString()).doesNotContainIgnoringCase("alice");
      assertThat(stored.get(0).contributors()).hasSize(1);
    }
  }

  @Test
  void cpBc06_severalHeldBundlesAreAllProcessedOnceTheKeyStoreReturns() throws Exception {
    AtomicBoolean vaultUp = new AtomicBoolean(false);
    PseudonymKey key = new PseudonymKey(new byte[32]);

    try (IngestPipeline pipeline =
        new IngestPipeline(
            () -> {
              if (!vaultUp.get()) {
                throw new KeyStoreUnavailableException("vault down");
              }
              return key;
            })) {
      pipeline.publish(bundleNaming("run-a", "alice@example.com"));
      pipeline.publish(bundleNaming("run-b", "bob@example.com"));
      Thread.sleep(1500);
      assertThat(pipeline.findings.findingsForRun("run-a")).isEmpty();
      assertThat(pipeline.findings.findingsForRun("run-b")).isEmpty();

      vaultUp.set(true);
      pipeline.awaitStored("run-a");
      pipeline.awaitStored("run-b");
    }
  }
}
