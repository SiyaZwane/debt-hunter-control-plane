package com.debthunter.controlplane.serve.http;

import static com.debthunter.controlplane.serve.http.ServeHarness.NOW;
import static com.debthunter.controlplane.serve.http.ServeHarness.PURPOSE;
import static com.debthunter.controlplane.serve.http.ServeHarness.THING;
import static com.debthunter.controlplane.serve.http.ServeHarness.WIDGETS;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.audit.AuditEntry;
import com.debthunter.controlplane.store.audit.AuditEntry.Kind;
import com.debthunter.controlplane.store.audit.AuditEntry.Outcome;
import com.debthunter.controlplane.store.audit.AuditRetention;
import com.debthunter.controlplane.store.evidence.EvidenceAccess;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.domain.Severity;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-91: the audit trails are complete for at least ninety days, and only an auditor can read
 * them. Every evidence retrieval — granted, refused or for a run that is not there — is on the
 * trail with who asked and why; nothing younger than ninety days is ever purged; and the roles that
 * make the audited changes cannot read the record of them.
 */
@Tag("integration")
class CpAc91_AuditLogComplete90DaysTest {

  private static String purpose() {
    return "?purpose=" + PURPOSE.replace(" ", "%20");
  }

  private static void seedRun(ServeHarness h, String runId, String repository) {
    h.run(
        runId,
        repository,
        "1.0.0",
        NOW,
        FindingState.OPEN,
        ServeHarness.finding("fp-" + runId, Severity.HIGH, "src/A.java"));
  }

  @Test
  void anAuditorSeesEveryEvidenceRetrievalWhoAskedWhyAndHowItEnded() {
    try (ServeHarness h = new ServeHarness()) {
      seedRun(h, "r1", WIDGETS);
      seedRun(h, "rb", THING);
      String ivy = h.investigator("team-a");
      h.get(ivy, "/api/v1/evidence/runs/r1" + purpose()).expectStatus().isOk();
      h.get(ivy, "/api/v1/evidence/runs/rb" + purpose()).expectStatus().isNotFound();
      h.get(ivy, "/api/v1/evidence/runs/nope" + purpose()).expectStatus().isNotFound();

      h.get(h.auditor(), "/api/v1/audit/evidence")
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.trail")
          .isEqualTo("EVIDENCE")
          .jsonPath("$.items.length()")
          .isEqualTo(3)
          .jsonPath("$.items[0].caller")
          .isEqualTo("ivy")
          .jsonPath("$.items[0].action")
          .isEqualTo("RETRIEVE_EVIDENCE")
          .jsonPath("$.items[0].target")
          .isEqualTo("r1 " + WIDGETS)
          .jsonPath("$.items[0].detail")
          .isEqualTo(PURPOSE)
          .jsonPath("$.items[0].outcome")
          .isEqualTo("GRANTED")
          .jsonPath("$.items[1].outcome")
          .isEqualTo("DENIED")
          .jsonPath("$.items[2].outcome")
          .isEqualTo("NOT_FOUND")
          .jsonPath("$.items[2].target")
          .isEqualTo("nope")
          .jsonPath("$.nextFrom")
          .isEmpty();
    }
  }

  @Test
  void entriesNinetyDaysOldAreStillThereAndThePurgeNeverReachesThem() {
    try (ServeHarness h = new ServeHarness()) {
      Instant eightyNine = NOW.minus(Duration.ofDays(89));
      h.accessLog.record(
          new EvidenceAccess(
              eightyNine, "ivy", "r-old", WIDGETS, PURPOSE, EvidenceAccess.Outcome.GRANTED));
      h.auditTrail.record(
          new AuditEntry(
              Kind.ADMIN,
              eightyNine,
              "admin",
              "ONBOARD_REPOSITORY",
              "repository=x",
              "",
              Outcome.ALLOWED));

      new AuditRetention(h.auditTrail, AuditRetention.MINIMUM, h.clock).purge();

      h.get(h.auditor(), "/api/v1/audit/admin")
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.items.length()")
          .isEqualTo(1)
          .jsonPath("$.items[0].at")
          .isEqualTo(eightyNine.toString());
      h.get(h.auditor(), "/api/v1/audit/evidence")
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.items.length()")
          .isEqualTo(1);
    }
  }

  @Test
  void everyTrailIsReadableAndAReIdentificationNeverShowsWhoWasRevealed() {
    try (ServeHarness h = new ServeHarness()) {
      h.auditTrail.record(
          new AuditEntry(Kind.ADMIN, NOW, "admin", "GRANT_WORKLOAD", "w r", "", Outcome.ALLOWED));
      h.auditTrail.record(
          new AuditEntry(
              Kind.REIDENTIFICATION,
              NOW,
              "officer",
              "REIDENTIFY",
              ServeHarness.CONTRIBUTOR,
              "case 42",
              Outcome.ALLOWED));
      h.auditTrail.record(
          new AuditEntry(
              Kind.SUPPRESSION, NOW, "alice", "SUPPRESS", "r fp", "why", Outcome.DENIED));
      String auditor = h.auditor();

      h.get(auditor, "/api/v1/audit/admin")
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.items[0].action")
          .isEqualTo("GRANT_WORKLOAD");
      h.get(auditor, "/api/v1/audit/reidentification")
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.items[0].caller")
          .isEqualTo("officer")
          .jsonPath("$.items[0].detail")
          .isEqualTo("case 42");
      h.get(auditor, "/api/v1/audit/suppression")
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.items[0].outcome")
          .isEqualTo("DENIED");
    }
  }

  @Test
  void onlyAnAuditorCanReadAnyTrail() {
    try (ServeHarness h = new ServeHarness()) {
      for (String trail : new String[] {"admin", "reidentification", "suppression", "evidence"}) {
        String path = "/api/v1/audit/" + trail;
        h.get(h.leader(), path).expectStatus().isForbidden();
        h.get(h.squad("team-a"), path).expectStatus().isForbidden();
        h.get(h.investigator("team-a"), path).expectStatus().isForbidden();
        h.get(h.token(List.of(), List.of()), path).expectStatus().isForbidden();
        h.get(null, path).expectStatus().isUnauthorized();
        h.get(h.auditor(), path).expectStatus().isOk();
      }
    }
  }

  @Test
  void aLongTrailIsReadInPagesWithNothingMissed() {
    try (ServeHarness h = new ServeHarness()) {
      for (int i = 0; i < 7; i++) {
        h.auditTrail.record(
            new AuditEntry(
                Kind.ADMIN, NOW.minusSeconds(100 - i), "admin", "A" + i, "t", "", Outcome.ALLOWED));
      }
      String auditor = h.auditor();

      byte[] first =
          h.get(auditor, "/api/v1/audit/admin?limit=3")
              .expectStatus()
              .isOk()
              .expectBody()
              .jsonPath("$.items.length()")
              .isEqualTo(3)
              .jsonPath("$.items[0].action")
              .isEqualTo("A0")
              .jsonPath("$.nextFrom")
              .isEqualTo(NOW.minusSeconds(98).toString())
              .returnResult()
              .getResponseBody();
      assertThat(new String(first)).contains("A2").doesNotContain("A3");

      h.get(auditor, "/api/v1/audit/admin?limit=3&from=" + NOW.minusSeconds(98))
          .expectStatus()
          .isOk()
          .expectBody()
          .jsonPath("$.items[0].action")
          .isEqualTo("A2")
          .jsonPath("$.items[2].action")
          .isEqualTo("A4")
          .jsonPath("$.items.length()")
          .isEqualTo(3)
          .jsonPath("$.nextFrom")
          .isEqualTo(NOW.minusSeconds(96).toString());
    }
  }

  @Test
  void anUnknownTrailAndABadLimitOrTimeAreRefused() {
    try (ServeHarness h = new ServeHarness()) {
      String auditor = h.auditor();

      h.get(auditor, "/api/v1/audit/secrets").expectStatus().isBadRequest();
      h.get(auditor, "/api/v1/audit/admin?limit=0").expectStatus().isBadRequest();
      h.get(auditor, "/api/v1/audit/admin?limit=501").expectStatus().isBadRequest();
      h.get(auditor, "/api/v1/audit/admin?limit=abc").expectStatus().isBadRequest();
      h.get(auditor, "/api/v1/audit/admin?from=yesterday").expectStatus().isBadRequest();
      h.get(auditor, "/api/v1/audit/admin?limit=500").expectStatus().isOk();
    }
  }
}
