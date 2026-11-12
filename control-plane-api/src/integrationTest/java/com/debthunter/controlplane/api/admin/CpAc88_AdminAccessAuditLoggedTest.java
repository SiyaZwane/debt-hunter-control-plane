package com.debthunter.controlplane.api.admin;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.audit.AuditEntry;
import com.debthunter.controlplane.store.audit.AuditEntry.Kind;
import com.debthunter.controlplane.store.audit.AuditEntry.Outcome;
import com.debthunter.controlplane.store.audit.AuditTrail;
import com.debthunter.controlplane.store.audit.JdbcAuditTrail;
import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

/**
 * CP-AC-88: every administrative change is recorded durably — in PostgreSQL, not only in the
 * application log — with who made it, what it was and when, and a change that cannot be recorded is
 * not made. (This lives with the administration API it exercises, in {@code control-plane-api}; the
 * store has no HTTP surface to drive.)
 */
@Tag("integration")
class CpAc88_AdminAccessAuditLoggedTest {

  private static final String REPOS = "/api/v1/admin/repositories";
  private static final String GRANTS = "/api/v1/admin/grants";
  private static final Instant FIXED = Instant.parse("2026-02-03T04:05:06Z");

  private static AuditTrail trail() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return new JdbcAuditTrail(dataSource);
  }

  @Test
  void everyAdministrativeChangeIsInTheDurableTrailWithWhoWhatAndWhen() {
    AuditTrail trail = trail();
    try (AdminHarness h = new AdminHarness(new TrailAdminAuditLog(trail))) {
      h.admin(HttpMethod.POST, REPOS, AdminHarness.widgets()).expectStatus().isCreated();
      h.admin(HttpMethod.PUT, GRANTS + "?workloadIdentity=svc-a&repository=acme/widgets", null)
          .expectStatus()
          .isNoContent();
      h.admin(HttpMethod.DELETE, REPOS + "/acme/widgets", null).expectStatus().isNoContent();

      List<AuditEntry> entries = trail.read(Kind.ADMIN, Instant.EPOCH, 100);

      assertThat(entries)
          .extracting(AuditEntry::action, AuditEntry::target)
          .containsExactly(
              org.assertj.core.groups.Tuple.tuple("ONBOARD_REPOSITORY", "repository=acme/widgets"),
              org.assertj.core.groups.Tuple.tuple(
                  "GRANT_WORKLOAD", "workload=svc-a repository=acme/widgets"),
              org.assertj.core.groups.Tuple.tuple(
                  "OFFBOARD_REPOSITORY", "repository=acme/widgets"));
      assertThat(entries)
          .allSatisfy(
              e -> {
                assertThat(e.caller()).isEqualTo(AdminHarness.ADMIN);
                assertThat(e.at()).isEqualTo(FIXED);
                assertThat(e.outcome()).isEqualTo(Outcome.ALLOWED);
              });
    }
  }

  @Test
  void aChangeIsNotMadeWhenTheDurableTrailCannotBeWritten() {
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
    try (AdminHarness h = new AdminHarness(new TrailAdminAuditLog(down))) {
      h.admin(HttpMethod.POST, REPOS, AdminHarness.widgets()).expectStatus().is5xxServerError();

      assertThat(h.registry.all()).isEmpty();
    }
  }

  @Test
  void aPipelineTokenCannotReachTheAdministrationApiSoLeavesNoAdminEntry() {
    AuditTrail trail = trail();
    try (AdminHarness h = new AdminHarness(new TrailAdminAuditLog(trail))) {
      h.call(HttpMethod.POST, REPOS, h.pipelineToken(), AdminHarness.widgets())
          .expectStatus()
          .isForbidden();

      assertThat(trail.read(Kind.ADMIN, Instant.EPOCH, 100)).isEmpty();
      assertThat(h.registry.all()).isEmpty();
    }
  }
}
