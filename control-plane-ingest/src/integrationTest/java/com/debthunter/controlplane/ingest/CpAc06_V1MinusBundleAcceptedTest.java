package com.debthunter.controlplane.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ingest.normalise.NormalisedRunBundle;
import com.debthunter.controlplane.testkit.RunBundleFixtures;
import com.debthunter.domain.Category;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-06: a bundle in the previous schema version (N-1) is accepted and processed, not rejected —
 * through the real queue, listener, and normaliser, not just the normaliser called directly.
 */
@Tag("integration")
class CpAc06_V1MinusBundleAcceptedTest {

  @Test
  void cpAc06_aPreviousVersionBundleIsNormalisedOffTheQueue() throws Exception {
    try (NormalisingPipeline pipeline = new NormalisingPipeline()) {
      NormalisedRunBundle bundle =
          pipeline.deliver(RunBundleFixtures.validV0("run-prev-1", "acme/widgets"));

      assertThat(bundle).isNotNull();
      assertThat(bundle.provenance().sourceSchemaVersion()).isEqualTo("0.9");
      assertThat(bundle.scan().run().id()).isEqualTo("run-prev-1");
      assertThat(bundle.scan().findings())
          .singleElement()
          .satisfies(
              f -> {
                assertThat(f.category()).isEqualTo(Category.HOTSPOT);
                assertThat(f.fingerprint()).isEqualTo("fp-0");
              });
      assertThat(pipeline.quarantinedRunIds()).isEmpty();
    }
  }

  @Test
  void cpAc06_aBundleThatCanNeverBeNormalisedIsSetAsideAndDoesNotBlockTheOnesBehindIt()
      throws Exception {
    try (NormalisingPipeline pipeline = new NormalisingPipeline()) {
      String unnormalisable =
          RunBundleFixtures.withValueAt(
              RunBundleFixtures.validV0("run-bad", "acme/widgets"),
              "/run/timestamp",
              new com.fasterxml.jackson.databind.node.TextNode("not a time"));

      pipeline.deliver(unnormalisable);
      NormalisedRunBundle next =
          pipeline.deliver(RunBundleFixtures.validV0("run-good", "acme/widgets"));

      assertThat(pipeline.quarantinedRunIds()).containsExactly("run-bad");
      assertThat(next).isNotNull();
      assertThat(next.scan().run().id()).isEqualTo("run-good");
    }
  }
}
