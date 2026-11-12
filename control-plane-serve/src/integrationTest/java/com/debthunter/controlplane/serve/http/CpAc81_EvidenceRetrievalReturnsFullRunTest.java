package com.debthunter.controlplane.serve.http;

import static com.debthunter.controlplane.serve.http.ServeHarness.CONTRIBUTOR;
import static com.debthunter.controlplane.serve.http.ServeHarness.NOW;
import static com.debthunter.controlplane.serve.http.ServeHarness.PURPOSE;
import static com.debthunter.controlplane.serve.http.ServeHarness.THING;
import static com.debthunter.controlplane.serve.http.ServeHarness.WIDGETS;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.domain.Severity;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CP-AC-81: given a run, the whole of what is held about it comes back. */
@Tag("integration")
class CpAc81_EvidenceRetrievalReturnsFullRunTest {

  private static String purpose() {
    return "?purpose=" + PURPOSE.replace(" ", "%20");
  }

  @Test
  void metadataFindingsEvidencePolicyAndOutcomeAllComeBack() {
    try (ServeHarness h = new ServeHarness()) {
      h.bundle("1.0.0", "a", null);
      h.run(
          "r1",
          WIDGETS,
          "1.0.0",
          NOW,
          FindingState.OPEN,
          ServeHarness.finding("fp1", Severity.HIGH, "src/A.java"),
          ServeHarness.finding("fp2", Severity.LOW, "src/B.java"));

      byte[] body =
          h.get(h.investigator("team-a"), "/api/v1/evidence/runs/r1" + purpose())
              .expectStatus()
              .isOk()
              .expectBody()
              .jsonPath("$.run.repository")
              .isEqualTo(WIDGETS)
              .jsonPath("$.run.commit")
              .isEqualTo("c-r1")
              .jsonPath("$.run.engines[0].id")
              .isEqualTo("code-maat")
              .jsonPath("$.findings.length()")
              .isEqualTo(2)
              .jsonPath("$.evidence.tier")
              .isEqualTo("HOT")
              .jsonPath("$.evidence.snapshots.length()")
              .isEqualTo(2)
              .jsonPath("$.policy.known")
              .isEqualTo(true)
              .jsonPath("$.policy.bundleVersion")
              .isEqualTo("1.0.0")
              .jsonPath("$.policy.digest")
              .isEqualTo("sha256:" + "a".repeat(64))
              .jsonPath("$.policy.outcome")
              .isEqualTo("FAILED")
              .returnResult()
              .getResponseBody();

      assertThat(new String(body))
          .doesNotContain(CONTRIBUTOR)
          .doesNotContainIgnoringCase("contributor");
    }
  }

  @Test
  void aRunOfAnotherSquadOrNoRunAtAllIs404AndNoTokenIs401() {
    try (ServeHarness h = new ServeHarness()) {
      h.run(
          "rb",
          THING,
          "1.0.0",
          NOW,
          FindingState.OPEN,
          ServeHarness.finding("fp3", Severity.HIGH, "src/C.java"));
      String ivy = h.investigator("team-a");

      h.get(ivy, "/api/v1/evidence/runs/rb" + purpose()).expectStatus().isNotFound();
      h.get(ivy, "/api/v1/evidence/runs/nope" + purpose()).expectStatus().isNotFound();
      h.get(null, "/api/v1/evidence/runs/rb" + purpose()).expectStatus().isUnauthorized();
      h.get(h.squad("team-a"), "/api/v1/evidence/runs/rb" + purpose()).expectStatus().isForbidden();
    }
  }

  @Test
  void aPurposeIsRequired() {
    try (ServeHarness h = new ServeHarness()) {
      h.run(
          "r1",
          WIDGETS,
          "1.0.0",
          NOW,
          FindingState.OPEN,
          ServeHarness.finding("fp1", Severity.HIGH, "src/A.java"));

      h.get(h.investigator("team-a"), "/api/v1/evidence/runs/r1").expectStatus().isBadRequest();
      h.get(h.investigator("team-a"), "/api/v1/evidence/runs/r1?purpose=short")
          .expectStatus()
          .isBadRequest();
    }
  }
}
