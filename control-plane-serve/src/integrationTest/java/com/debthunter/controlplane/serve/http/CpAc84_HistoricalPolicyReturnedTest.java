package com.debthunter.controlplane.serve.http;

import static com.debthunter.controlplane.serve.http.ServeHarness.NOW;
import static com.debthunter.controlplane.serve.http.ServeHarness.PURPOSE;
import static com.debthunter.controlplane.serve.http.ServeHarness.WIDGETS;

import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.WorkItemRoute;
import com.debthunter.domain.Severity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CP-AC-84: a run is explained by the policy that applied to it, not the one in force now. */
@Tag("integration")
class CpAc84_HistoricalPolicyReturnedTest {

  private static String purpose() {
    return "?purpose=" + PURPOSE.replace(" ", "%20");
  }

  @Test
  void anOldRunReturnsTheBundleItWasAssessedAgainstEvenAfterItWasSuperseded() {
    try (ServeHarness h = new ServeHarness()) {
      h.bundle("1.0.0", "a", "2.0.0");
      h.bundle("2.0.0", "b", null);
      h.run(
          "old",
          WIDGETS,
          "1.0.0",
          NOW.minusSeconds(86_400L * 40),
          FindingState.OPEN,
          ServeHarness.finding("fp1", Severity.HIGH, "src/A.java"));
      h.run(
          "new",
          WIDGETS,
          "2.0.0",
          NOW,
          FindingState.OPEN,
          ServeHarness.finding("fp1", Severity.HIGH, "src/A.java"));
      // The repository has since moved to the newer bundle.
      h.registry.save(
          new OnboardedRepository(
              WIDGETS, "team-a", "tier-1", "2.0.0", "STAGE_3", new WorkItemRoute("jira", "DH")));
      String ivy = h.investigator("team-a");

      h.get(ivy, "/api/v1/evidence/runs/old" + purpose())
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.policy.bundleVersion")
          .isEqualTo("1.0.0")
          .jsonPath("$.policy.digest")
          .isEqualTo("sha256:" + "a".repeat(64))
          .jsonPath("$.policy.supersededBy")
          .isEqualTo("2.0.0");
      h.get(ivy, "/api/v1/evidence/runs/new" + purpose())
          .expectBody()
          .jsonPath("$.policy.bundleVersion")
          .isEqualTo("2.0.0")
          .jsonPath("$.policy.digest")
          .isEqualTo("sha256:" + "b".repeat(64));
    }
  }

  @Test
  void aRunReceivedBeforeTheVersionWasRecordedSaysItIsUnknownRatherThanGuessing() {
    try (ServeHarness h = new ServeHarness()) {
      h.bundle("2.0.0", "b", null);
      h.run(
          "legacy",
          WIDGETS,
          null,
          NOW,
          FindingState.OPEN,
          ServeHarness.finding("fp1", Severity.HIGH, "src/A.java"));

      h.get(h.investigator("team-a"), "/api/v1/evidence/runs/legacy" + purpose())
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.policy.known")
          .isEqualTo(false)
          .jsonPath("$.policy.bundleVersion")
          .doesNotExist()
          .jsonPath("$.policy.outcome")
          .isEqualTo("FAILED");
    }
  }
}
