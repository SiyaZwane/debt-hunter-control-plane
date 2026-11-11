package com.debthunter.controlplane.api.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.testkit.RunBundleFixtures;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;

/**
 * CP-AC-01: a valid run bundle from an authenticated, in-scope pipeline is acknowledged with 202
 * and placed on the intake queue — through the real security chain, not a stand-in for it.
 */
@Tag("integration")
class CpAc01_ValidBundleReturns202Test {

  @Test
  void cpAc01_validBundleReturns202AndIsEnqueued() {
    try (IngestionHarness harness = IngestionHarness.builder().start()) {
      Map<String, Object> body =
          harness
              .publish(harness.tokenFor(IngestionHarness.SERVICE), RunBundleFixtures.validV1())
              .expectStatus()
              .isAccepted()
              .expectBody(new ParameterizedTypeReference<Map<String, Object>>() {})
              .returnResult()
              .getResponseBody();

      assertThat(body).containsOnlyKeys("correlationId");
      assertThat(harness.queue.published()).hasSize(1);
      assertThat(harness.queue.published().get(0).correlationId().toString())
          .isEqualTo(body.get("correlationId"));
    }
  }

  @Test
  void cpAc01_aPreviousVersionBundleIsAlsoAccepted() {
    try (IngestionHarness harness = IngestionHarness.builder().start()) {
      harness
          .publish(harness.tokenFor(IngestionHarness.SERVICE), RunBundleFixtures.validV0())
          .expectStatus()
          .isAccepted();

      assertThat(harness.queue.published()).hasSize(1);
    }
  }

  @Test
  void cpAc01_aReportTheRealDataPlaneProducedIsAccepted() {
    // The golden report declares a local path as its repository, as the real data plane does today.
    String declared = "/work/acme/widgets";
    try (IngestionHarness harness =
        IngestionHarness.builder()
            .scopes(Map.of(IngestionHarness.SERVICE, List.of(declared)))
            .start()) {
      harness
          .publish(
              harness.tokenFor(IngestionHarness.SERVICE), RunBundleFixtures.realDataPlaneReport())
          .expectStatus()
          .isAccepted();
    }
  }
}
