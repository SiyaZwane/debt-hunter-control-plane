package com.debthunter.controlplane.api.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.testkit.RunBundleFixtures;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-BC-04: a burst from one pipeline is throttled with 429 and a retry hint, without affecting any
 * other pipeline, and the limit recovers once the window passes.
 *
 * <p>The prompt names this boundary condition without specifying it; this is the interpretation
 * implemented: a per-workload-identity sliding-window rate limit.
 */
@Tag("integration")
class CpBc04_BurstThrottlingTest {

  @Test
  void cpBc04_aBurstIsThrottledPerCallerAndRecoversAfterTheWindow() {
    IngestionHarness[] holder = new IngestionHarness[1];
    TestClock clock = new TestClock(java.time.Instant.parse("2026-02-03T04:05:06Z"));
    RequestRateLimiter limiter =
        new SlidingWindowRequestRateLimiter(3, Duration.ofMinutes(1), clock);
    try (IngestionHarness harness =
        IngestionHarness.builder()
            .scopes(
                Map.of(
                    "svc-a", List.of("acme/widgets"),
                    "svc-b", List.of("acme/widgets")))
            .rateLimiter(limiter)
            .start()) {
      String burster = harness.tokenFor("svc-a");
      String bystander = harness.tokenFor("svc-b");
      String bundle = RunBundleFixtures.validV1();

      for (int i = 0; i < 3; i++) {
        harness.publish(burster, bundle).expectStatus().isAccepted();
      }
      harness
          .publish(burster, bundle)
          .expectStatus()
          .isEqualTo(429)
          .expectHeader()
          .exists("Retry-After")
          .expectBody()
          .jsonPath("$.error")
          .isEqualTo("rate_limited");

      // Another pipeline is untouched by svc-a's burst.
      harness.publish(bystander, bundle).expectStatus().isAccepted();
      assertThat(harness.queue.published()).hasSize(4);

      // After the window the burster is let back in.
      clock.advance(Duration.ofMinutes(1));
      harness.publish(burster, bundle).expectStatus().isAccepted();
      assertThat(harness.queue.published()).hasSize(5);
    }
  }
}
