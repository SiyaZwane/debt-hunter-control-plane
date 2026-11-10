package com.debthunter.controlplane.api.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.testkit.RunBundleFixtures;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-03: a bundle that does not conform to the schema is refused with 400 and a machine-readable
 * explanation of what is wrong, and never reaches the queue.
 */
@Tag("integration")
class CpAc03_InvalidSchemaReturns400Test {

  @Test
  void cpAc03_aBundleMissingARequiredFieldIsRejectedWithMachineReadableDetails() {
    try (IngestionHarness harness = IngestionHarness.builder().start()) {
      String bundle =
          RunBundleFixtures.withoutField(RunBundleFixtures.validV1(), "/run/historyDepth");

      harness
          .publish(harness.tokenFor(IngestionHarness.SERVICE), bundle)
          .expectStatus()
          .isBadRequest()
          .expectBody()
          .jsonPath("$.error")
          .isEqualTo("schema_validation_failed")
          .jsonPath("$.details[0].path")
          .isEqualTo("$.run")
          .jsonPath("$.details[0].message")
          .value(message -> assertThat((String) message).contains("historyDepth"));

      assertThat(harness.queue.published()).isEmpty();
    }
  }

  @Test
  void cpAc03_aBodyThatIsNotJsonIsRejected() {
    try (IngestionHarness harness = IngestionHarness.builder().start()) {
      harness
          .publish(harness.tokenFor(IngestionHarness.SERVICE), RunBundleFixtures.notJson())
          .expectStatus()
          .isBadRequest()
          .expectBody()
          .jsonPath("$.error")
          .isEqualTo("invalid_json");
    }
  }
}
