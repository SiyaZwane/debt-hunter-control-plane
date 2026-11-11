package com.debthunter.controlplane.api.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.testkit.RunBundleFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-BC-01: only the current schema version and the one before it are accepted. Once the data plane
 * has moved on a major version, bundles from two versions back are refused — with a response that
 * says which versions would have been accepted — instead of being half-understood.
 */
@Tag("integration")
class CpBc01_OldSchemaVersionRejectedTest {

  @Test
  void cpBc01_aBundleOlderThanThePreviousVersionIsRejectedAndTheAcceptedVersionsAreListed() {
    SchemaSource classpath = new ClasspathSchemaSource();
    // The data plane has moved to major 2 (reusing v1's schema as a stand-in for "2"), so the
    // window is now {2, 1} and a v0 bundle is two versions back.
    RunBundleSchemaValidator movedOn =
        new RunBundleSchemaValidator(2, major -> classpath.schemaFor(major == 2 ? 1 : major));
    try (IngestionHarness harness = IngestionHarness.builder().schemaValidator(movedOn).start()) {
      String token = harness.tokenFor(IngestionHarness.SERVICE);

      harness
          .publish(token, RunBundleFixtures.validV0())
          .expectStatus()
          .isBadRequest()
          .expectBody()
          .jsonPath("$.error")
          .isEqualTo("unsupported_schema_version")
          .jsonPath("$.message")
          .value(message -> assertThat((String) message).contains("older"))
          .jsonPath("$.supportedMajors[0]")
          .isEqualTo(2)
          .jsonPath("$.supportedMajors[1]")
          .isEqualTo(1);

      assertThat(harness.queue.published()).isEmpty();

      // The previous version is still inside the window.
      harness.publish(token, RunBundleFixtures.validV1()).expectStatus().isAccepted();
    }
  }
}
