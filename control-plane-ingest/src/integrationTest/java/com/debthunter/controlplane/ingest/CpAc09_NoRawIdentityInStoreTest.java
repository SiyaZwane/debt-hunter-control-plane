package com.debthunter.controlplane.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ingest.pseudonymise.InMemoryPseudonymKeyStore;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.identity.Pseudonym;
import com.debthunter.controlplane.testkit.RunBundleFixtures;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-09: contributor identity never reaches the system of record. A bundle that names people is
 * published to a real queue and driven through the real pipeline, and what lands in the finding
 * store is inspected for any trace of them.
 */
@Tag("integration")
class CpAc09_NoRawIdentityInStoreTest {

  private static final ObjectMapper JSON = new ObjectMapper();

  private static String bundleNaming(String runId, String... people) throws Exception {
    ObjectNode root = (ObjectNode) JSON.readTree(RunBundleFixtures.validV1(runId, "acme/widgets"));
    ObjectNode evidence = (ObjectNode) root.at("/findings/0/evidence");
    var authors = evidence.putArray("authors");
    for (String person : people) {
      authors.add(person);
    }
    evidence.put("lastEditor", people[0]);
    return JSON.writeValueAsString(root);
  }

  @Test
  void cpAc09_noRawIdentityIsInTheStoreAfterIngest() throws Exception {
    try (IngestPipeline pipeline =
        new IngestPipeline(new InMemoryPseudonymKeyStore(new byte[32]))) {
      pipeline.publish(bundleNaming("run-1", "alice@example.com", "Bob Builder"));
      pipeline.awaitStored("run-1");

      List<PseudonymisedFinding> stored = pipeline.findings.findingsForRun("run-1");
      assertThat(stored.toString())
          .doesNotContainIgnoringCase("alice")
          .doesNotContainIgnoringCase("example.com")
          .doesNotContainIgnoringCase("builder");
      assertThat(stored.get(0).contributors()).hasSize(2);
      assertThat(stored.get(0).evidence()).doesNotContainKeys("authors", "lastEditor");
    }
  }

  @Test
  void cpAc09_theOnlyPlaceAnIdentityCanBeRecoveredFromIsTheSeparateDirectory() throws Exception {
    try (IngestPipeline pipeline =
        new IngestPipeline(new InMemoryPseudonymKeyStore(new byte[32]))) {
      pipeline.publish(bundleNaming("run-2", "alice@example.com"));
      pipeline.awaitStored("run-2");

      Pseudonym alice = pipeline.findings.findingsForRun("run-2").get(0).contributors().get(0);
      assertThat(pipeline.directory.lookup(alice)).contains("alice@example.com");
    }
  }

  @Test
  void cpAc09_theSameRunDeliveredTwiceLeavesOneCopyOfPseudonymsNotTwo() throws Exception {
    try (IngestPipeline pipeline =
        new IngestPipeline(new InMemoryPseudonymKeyStore(new byte[32]))) {
      String bundle = bundleNaming("run-3", "alice@example.com");
      pipeline.publish(bundle);
      pipeline.publish(bundle);
      pipeline.awaitStored("run-3");

      assertThat(pipeline.findings.findingsForRun("run-3")).hasSize(1);
    }
  }
}
