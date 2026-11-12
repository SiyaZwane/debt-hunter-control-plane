package com.debthunter.controlplane.serve.http;

import static com.debthunter.controlplane.serve.http.ServeHarness.NOW;
import static com.debthunter.controlplane.serve.http.ServeHarness.PURPOSE;
import static com.debthunter.controlplane.serve.http.ServeHarness.THING;
import static com.debthunter.controlplane.serve.http.ServeHarness.WIDGETS;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.evidence.EvidenceAccess;
import com.debthunter.controlplane.store.evidence.EvidenceAccessLog;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.domain.Severity;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** CP-AC-83: every retrieval is recorded with who asked and why, and none is served unrecorded. */
@Tag("integration")
class CpAc83_EvidenceAccessAuditedTest {

  private static String purpose() {
    return "?purpose=" + PURPOSE.replace(" ", "%20");
  }

  @Test
  void everyRequestIsRecordedWithTheCallerThePurposeAndTheOutcome() {
    try (ServeHarness h = new ServeHarness()) {
      h.run(
          "r1",
          WIDGETS,
          "1.0.0",
          NOW,
          FindingState.OPEN,
          ServeHarness.finding("fp1", Severity.HIGH, "src/A.java"));
      h.run(
          "rb",
          THING,
          "1.0.0",
          NOW,
          FindingState.OPEN,
          ServeHarness.finding("fp3", Severity.HIGH, "src/C.java"));
      String ivy = h.investigator("team-a");

      h.get(ivy, "/api/v1/evidence/runs/r1" + purpose()).expectStatus().isOk();
      h.get(ivy, "/api/v1/evidence/runs/rb" + purpose()).expectStatus().isNotFound();
      h.get(ivy, "/api/v1/evidence/runs/nope" + purpose()).expectStatus().isNotFound();

      assertThat(h.accessLog.since(Instant.EPOCH))
          .extracting(
              EvidenceAccess::caller,
              EvidenceAccess::runId,
              EvidenceAccess::purpose,
              EvidenceAccess::outcome)
          .containsExactly(
              org.assertj.core.groups.Tuple.tuple(
                  "ivy", "r1", PURPOSE, EvidenceAccess.Outcome.GRANTED),
              org.assertj.core.groups.Tuple.tuple(
                  "ivy", "rb", PURPOSE, EvidenceAccess.Outcome.DENIED),
              org.assertj.core.groups.Tuple.tuple(
                  "ivy", "nope", PURPOSE, EvidenceAccess.Outcome.NOT_FOUND));
    }
  }

  @Test
  void aRequestThatCannotBeRecordedIsRefusedAndReleasesNothing() {
    EvidenceAccessLog broken =
        new EvidenceAccessLog() {
          @Override
          public void record(EvidenceAccess access) {
            throw new IllegalStateException("audit store down");
          }

          @Override
          public List<EvidenceAccess> since(Instant from) {
            return List.of();
          }

          @Override
          public List<EvidenceAccess> since(Instant from, int limit) {
            return List.of();
          }
        };
    try (ServeHarness h = new ServeHarness(NOW, real -> broken)) {
      h.run(
          "r1",
          WIDGETS,
          "1.0.0",
          NOW,
          FindingState.OPEN,
          ServeHarness.finding("fp1", Severity.HIGH, "src/A.java"));

      String body =
          new String(
              h.get(h.investigator("team-a"), "/api/v1/evidence/runs/r1" + purpose())
                  .expectStatus()
                  .isEqualTo(503)
                  .expectBody()
                  .jsonPath("$.error")
                  .isEqualTo("unavailable")
                  .returnResult()
                  .getResponseBody());

      assertThat(body)
          .doesNotContain("codemaat")
          .doesNotContain("src/A.java")
          .doesNotContain("audit store");
    }
  }

  @Test
  void anInvalidRequestIsNotRecordedAsAnAccess() {
    try (ServeHarness h = new ServeHarness()) {
      h.run(
          "r1",
          WIDGETS,
          "1.0.0",
          NOW,
          FindingState.OPEN,
          ServeHarness.finding("fp1", Severity.HIGH, "src/A.java"));

      h.get(h.investigator("team-a"), "/api/v1/evidence/runs/r1").expectStatus().isBadRequest();

      assertThat(h.accessLog.since(Instant.EPOCH)).isEmpty();
    }
  }
}
