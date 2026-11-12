package com.debthunter.controlplane.serve.http;

import static com.debthunter.controlplane.serve.http.ServeHarness.NOW;
import static com.debthunter.controlplane.serve.http.ServeHarness.PURPOSE;
import static com.debthunter.controlplane.serve.http.ServeHarness.WIDGETS;

import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.domain.Severity;
import java.time.Duration;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-82: once detail has aged into the warm tier it is still reachable, as summaries, marked so.
 */
@Tag("integration")
class CpAc82_WarmTierEvidenceAccessibleTest {

  private static String purpose() {
    return "?purpose=" + PURPOSE.replace(" ", "%20");
  }

  @Test
  void anAgedRunReturnsItsWarmSummariesWithoutTheCallerDoingAnythingDifferent() {
    try (ServeHarness h = new ServeHarness()) {
      h.run(
          "r1",
          WIDGETS,
          "1.0.0",
          NOW,
          FindingState.OPEN,
          ServeHarness.finding("fp1", Severity.HIGH, "src/A.java"),
          ServeHarness.finding("fp2", Severity.HIGH, "src/B.java"));
      String ivy = h.investigator("team-a");
      h.get(ivy, "/api/v1/evidence/runs/r1" + purpose())
          .expectBody()
          .jsonPath("$.evidence.tier")
          .isEqualTo("HOT");

      int moved = h.evidence.moveExpiredToWarm(NOW.plus(Duration.ofDays(90)));

      org.assertj.core.api.Assertions.assertThat(moved).isEqualTo(2);
      h.get(ivy, "/api/v1/evidence/runs/r1" + purpose())
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.evidence.tier")
          .isEqualTo("WARM")
          .jsonPath("$.evidence.snapshots.length()")
          .isEqualTo(0)
          .jsonPath("$.evidence.summaries[0].ruleId")
          .isEqualTo("codemaat.hotspot")
          .jsonPath("$.evidence.summaries[0].findingCount")
          .isEqualTo(2)
          .jsonPath("$.findings.length()")
          .isEqualTo(2)
          .jsonPath("$.run.repository")
          .isEqualTo(WIDGETS);
    }
  }
}
