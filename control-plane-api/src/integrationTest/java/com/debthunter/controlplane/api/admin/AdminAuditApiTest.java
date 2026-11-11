package com.debthunter.controlplane.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

@Tag("integration")
class AdminAuditApiTest {

  private static final String REPOS = "/api/v1/admin/repositories";
  private static final String GRANTS = "/api/v1/admin/grants";

  @Test
  void everyChangeIsRecordedWithWhoDidWhatToWhatAndWhen() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.POST, REPOS, AdminHarness.widgets()).expectStatus().isCreated();
      h.admin(HttpMethod.PUT, GRANTS + "?workloadIdentity=svc-a&repository=acme/widgets", null)
          .expectStatus()
          .isNoContent();
      h.admin(HttpMethod.PUT, REPOS + "/acme/widgets", AdminHarness.widgets())
          .expectStatus()
          .isOk();
      h.admin(HttpMethod.DELETE, GRANTS + "?workloadIdentity=svc-a&repository=acme/widgets", null)
          .expectStatus()
          .isNoContent();
      h.admin(HttpMethod.DELETE, REPOS + "/acme/widgets", null).expectStatus().isNoContent();

      assertThat(h.auditTrail)
          .extracting(AdminAuditEntry::action, AdminAuditEntry::target)
          .containsExactly(
              org.assertj.core.groups.Tuple.tuple("ONBOARD_REPOSITORY", "repository=acme/widgets"),
              org.assertj.core.groups.Tuple.tuple(
                  "GRANT_WORKLOAD", "workload=svc-a repository=acme/widgets"),
              org.assertj.core.groups.Tuple.tuple("UPDATE_REPOSITORY", "repository=acme/widgets"),
              org.assertj.core.groups.Tuple.tuple(
                  "REVOKE_WORKLOAD", "workload=svc-a repository=acme/widgets"),
              org.assertj.core.groups.Tuple.tuple(
                  "OFFBOARD_REPOSITORY", "repository=acme/widgets"));
      assertThat(h.auditTrail)
          .allSatisfy(
              e -> {
                assertThat(e.caller()).isEqualTo(AdminHarness.ADMIN);
                assertThat(e.at()).isEqualTo(Instant.parse("2026-02-03T04:05:06Z"));
              });
    }
  }

  @Test
  void readsAreNotAudited() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.GET, REPOS, null).expectStatus().isOk();
      h.admin(HttpMethod.GET, REPOS + "/acme/none", null).expectStatus().isNotFound();
      h.admin(HttpMethod.GET, GRANTS + "?workloadIdentity=svc", null).expectStatus().isOk();

      assertThat(h.auditTrail).isEmpty();
    }
  }

  @Test
  void aRejectedAttemptToChangeThingsIsStillRecordedBecauseItWasAnAttempt() {
    try (AdminHarness h = new AdminHarness()) {
      h.admin(HttpMethod.POST, REPOS, AdminHarness.widgets()).expectStatus().isCreated();

      h.admin(HttpMethod.POST, REPOS, AdminHarness.widgets()).expectStatus().isEqualTo(409);

      assertThat(h.auditTrail)
          .extracting(AdminAuditEntry::action)
          .containsExactly("ONBOARD_REPOSITORY", "ONBOARD_REPOSITORY");
    }
  }

  @Test
  void ifTheAuditTrailCannotBeWrittenTheChangeIsNotMade() {
    AdminAuditLog broken =
        entry -> {
          throw new IllegalStateException("audit sink down");
        };
    try (AdminHarness h = new AdminHarness(broken)) {
      h.admin(HttpMethod.POST, REPOS, AdminHarness.widgets())
          .expectStatus()
          .is5xxServerError()
          .expectBody()
          .jsonPath("$.error")
          .isEqualTo("internal_error");

      assertThat(h.registry.all()).isEmpty();
    }
  }

  @Test
  void aFailedAuditOfAGrantLeavesNoGrantBehind() {
    AdminAuditLog broken =
        entry -> {
          if (entry.action().equals("GRANT_WORKLOAD")) {
            throw new IllegalStateException("audit sink down");
          }
        };
    try (AdminHarness h = new AdminHarness(broken)) {
      h.admin(HttpMethod.POST, REPOS, AdminHarness.widgets()).expectStatus().isCreated();

      h.admin(HttpMethod.PUT, GRANTS + "?workloadIdentity=svc-a&repository=acme/widgets", null)
          .expectStatus()
          .is5xxServerError();

      assertThat(h.registry.mayWrite("svc-a", "acme/widgets")).isFalse();
    }
  }
}
