package com.debthunter.controlplane.serve.http;

import static com.debthunter.controlplane.serve.http.ServeHarness.GADGETS;
import static com.debthunter.controlplane.serve.http.ServeHarness.NOW;
import static com.debthunter.controlplane.serve.http.ServeHarness.THING;
import static com.debthunter.controlplane.serve.http.ServeHarness.WIDGETS;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.audit.AuditEntry;
import com.debthunter.controlplane.store.audit.AuditEntry.Kind;
import com.debthunter.controlplane.store.audit.AuditEntry.Outcome;
import com.debthunter.controlplane.store.audit.AuditTrail;
import com.debthunter.controlplane.store.suppressions.Suppression;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-86: a squad may suppress findings only in repositories it owns. Naming another squad's
 * repository, another squad, or a repository that does not exist is refused, nothing is suppressed,
 * and the attempt is on the audit trail. (This lives in {@code control-plane-serve}, where the
 * squad API is; the api module cannot depend on it.)
 */
@Tag("integration")
class CpAc86_WrongSquadSuppressionRejectedTest {

  private static final String REASON = "accepted until the module is retired";

  private static Map<String, Object> request(
      String repository, String fingerprint, Instant expiry) {
    return Map.of(
        "repository", repository,
        "fingerprint", fingerprint,
        "reason", REASON,
        "expiresAt", expiry.toString());
  }

  private static Instant in(int days) {
    return NOW.plus(Duration.ofDays(days));
  }

  @Test
  void aSquadMemberSuppressesAFindingInTheirOwnRepositoryAndItIsRecorded() {
    try (ServeHarness h = new ServeHarness()) {
      h.post(
              h.squad("team-a"),
              "/api/v1/squads/team-a/suppressions",
              request(WIDGETS, "fp1", in(30)))
          .expectStatus()
          .isCreated()
          .expectBody()
          .jsonPath("$.repository")
          .isEqualTo(WIDGETS)
          .jsonPath("$.fingerprint")
          .isEqualTo("fp1")
          .jsonPath("$.reason")
          .isEqualTo(REASON)
          .jsonPath("$.id")
          .isNotEmpty();

      Suppression stored = h.suppressionRepository.find(WIDGETS, "fp1").orElseThrow();
      assertThat(stored.expiresAt()).isEqualTo(in(30));
      assertThat(stored.createdAt()).isEqualTo(NOW);
      assertThat(h.auditTrail.read(Kind.SUPPRESSION, Instant.EPOCH, 10))
          .containsExactly(
              new AuditEntry(
                  Kind.SUPPRESSION,
                  NOW,
                  "alice",
                  "SUPPRESS",
                  WIDGETS + " fp1",
                  REASON,
                  Outcome.ALLOWED));
    }
  }

  @Test
  void anotherSquadsRepositoryIsRefusedNothingIsSuppressedAndTheAttemptIsRecorded() {
    try (ServeHarness h = new ServeHarness()) {
      h.post(h.squad("team-a"), "/api/v1/squads/team-a/suppressions", request(THING, "fp9", in(30)))
          .expectStatus()
          .isForbidden()
          .expectBody()
          .jsonPath("$.error")
          .isEqualTo("forbidden");

      assertThat(h.suppressionRepository.find(THING, "fp9")).isEmpty();
      assertThat(h.auditTrail.read(Kind.SUPPRESSION, Instant.EPOCH, 10))
          .extracting(AuditEntry::caller, AuditEntry::target, AuditEntry::outcome)
          .containsExactly(
              org.assertj.core.groups.Tuple.tuple("alice", THING + " fp9", Outcome.DENIED));
    }
  }

  @Test
  void actingAsASquadTheCallerIsNotInIsRefused() {
    try (ServeHarness h = new ServeHarness()) {
      h.post(h.squad("team-a"), "/api/v1/squads/team-b/suppressions", request(THING, "fp9", in(30)))
          .expectStatus()
          .isForbidden();

      assertThat(h.suppressionRepository.find(THING, "fp9")).isEmpty();
    }
  }

  @Test
  void aRepositoryThatDoesNotExistIsRefusedTheSameWayAsAForeignOne() {
    try (ServeHarness h = new ServeHarness()) {
      h.post(
              h.squad("team-a"),
              "/api/v1/squads/team-a/suppressions",
              request("acme/missing", "fp1", in(30)))
          .expectStatus()
          .isForbidden()
          .expectBody()
          .jsonPath("$.message")
          .isEqualTo("That repository does not belong to the squad");
    }
  }

  @Test
  void aSquadMemberCannotSlipAnotherRepositoryInBesideTheirSquadsName() {
    try (ServeHarness h = new ServeHarness()) {
      // A caller in both squads still acts for the squad named in the path.
      String both = h.token(List.of("ControlPlane.Squad"), List.of("team-a", "team-b"));
      h.post(both, "/api/v1/squads/team-a/suppressions", request(THING, "fp9", in(30)))
          .expectStatus()
          .isForbidden();
      h.post(both, "/api/v1/squads/team-b/suppressions", request(THING, "fp9", in(30)))
          .expectStatus()
          .isCreated();
    }
  }

  @Test
  void rolesOtherThanSquadCannotSuppressAndLeaveNoEntryAsItNeverGotThatFar() {
    try (ServeHarness h = new ServeHarness()) {
      for (String token : new String[] {h.leader(), h.investigator("team-a"), h.auditor()}) {
        h.post(token, "/api/v1/squads/team-a/suppressions", request(WIDGETS, "fp1", in(30)))
            .expectStatus()
            .isForbidden();
      }
      h.post(null, "/api/v1/squads/team-a/suppressions", request(WIDGETS, "fp1", in(30)))
          .expectStatus()
          .isUnauthorized();

      assertThat(h.suppressionRepository.find(WIDGETS, "fp1")).isEmpty();
    }
  }

  @Test
  void theExpiryMustBeInTheFutureAndWithinThePolicyCap() {
    try (ServeHarness h = new ServeHarness()) {
      String alice = h.squad("team-a");
      String path = "/api/v1/squads/team-a/suppressions";

      h.post(alice, path, request(GADGETS, "fp1", NOW.minusSeconds(1)))
          .expectStatus()
          .isBadRequest();
      h.post(alice, path, request(GADGETS, "fp1", NOW)).expectStatus().isBadRequest();
      h.post(alice, path, request(GADGETS, "fp1", in(90).plusSeconds(1)))
          .expectStatus()
          .isBadRequest()
          .expectBody()
          .jsonPath("$.message")
          .isEqualTo("A suppression may not run longer than 90 days");
      assertThat(h.suppressionRepository.find(GADGETS, "fp1")).isEmpty();

      h.post(alice, path, request(GADGETS, "fp1", in(90))).expectStatus().isCreated();
    }
  }

  @Test
  void aSuppressionNeedsARealReasonAndAFindingToApplyTo() {
    try (ServeHarness h = new ServeHarness()) {
      String alice = h.squad("team-a");
      String path = "/api/v1/squads/team-a/suppressions";

      h.post(
              alice,
              path,
              Map.of(
                  "repository",
                  WIDGETS,
                  "fingerprint",
                  "fp1",
                  "reason",
                  "short",
                  "expiresAt",
                  in(5).toString()))
          .expectStatus()
          .isBadRequest();
      h.post(
              alice,
              path,
              Map.of("repository", WIDGETS, "reason", REASON, "expiresAt", in(5).toString()))
          .expectStatus()
          .isBadRequest();
      h.post(
              alice,
              path,
              Map.of("fingerprint", "fp1", "reason", REASON, "expiresAt", in(5).toString()))
          .expectStatus()
          .isBadRequest();
      h.post(alice, path, Map.of("repository", WIDGETS, "fingerprint", "fp1", "reason", REASON))
          .expectStatus()
          .isBadRequest();

      assertThat(h.suppressionRepository.find(WIDGETS, "fp1")).isEmpty();
    }
  }

  @Test
  void aSuppressionThatCannotBeRecordedIsNotGranted() {
    AuditTrail down =
        new AuditTrail() {
          @Override
          public void record(AuditEntry entry) {
            throw new IllegalStateException("audit store down");
          }

          @Override
          public List<AuditEntry> read(Kind kind, Instant from, int limit) {
            return List.of();
          }
        };
    try (ServeHarness h = new ServeHarness(NOW, log -> log, real -> down)) {
      h.post(
              h.squad("team-a"),
              "/api/v1/squads/team-a/suppressions",
              request(WIDGETS, "fp1", in(30)))
          .expectStatus()
          .isEqualTo(503);

      assertThat(h.suppressionRepository.find(WIDGETS, "fp1")).isEmpty();
    }
  }

  @Test
  void aSquadWithdrawsItsOwnSuppressionButNotAnotherSquadsOrOneThatIsNotThere() {
    try (ServeHarness h = new ServeHarness()) {
      String alice = h.squad("team-a");
      String bob = h.token(List.of("ControlPlane.Squad"), List.of("team-b"));
      String mine =
          idOf(
              h.post(alice, "/api/v1/squads/team-a/suppressions", request(WIDGETS, "fp1", in(30))));
      String theirs =
          idOf(h.post(bob, "/api/v1/squads/team-b/suppressions", request(THING, "fp2", in(30))));

      h.delete(alice, "/api/v1/squads/team-a/suppressions/" + theirs).expectStatus().isNotFound();
      h.delete(alice, "/api/v1/squads/team-a/suppressions/nope").expectStatus().isNotFound();
      h.delete(alice, "/api/v1/squads/team-b/suppressions/" + theirs).expectStatus().isForbidden();
      assertThat(h.suppressionRepository.find(THING, "fp2")).isPresent();

      h.delete(alice, "/api/v1/squads/team-a/suppressions/" + mine).expectStatus().isNoContent();
      assertThat(h.suppressionRepository.find(WIDGETS, "fp1")).isEmpty();
      assertThat(h.auditTrail.read(Kind.SUPPRESSION, Instant.EPOCH, 50))
          .extracting(AuditEntry::caller, AuditEntry::action, AuditEntry::outcome)
          .contains(
              org.assertj.core.groups.Tuple.tuple("alice", "REVOKE_SUPPRESSION", Outcome.ALLOWED),
              org.assertj.core.groups.Tuple.tuple("alice", "REVOKE_SUPPRESSION", Outcome.DENIED));
    }
  }

  private static String idOf(
      org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec r) {
    byte[] body = r.expectStatus().isCreated().expectBody().returnResult().getResponseBody();
    String json = new String(body);
    int at = json.indexOf("\"id\":\"") + 6;
    return json.substring(at, json.indexOf('"', at));
  }
}
