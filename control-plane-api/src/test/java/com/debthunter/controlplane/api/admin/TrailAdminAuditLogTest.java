package com.debthunter.controlplane.api.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.store.audit.AuditEntry;
import com.debthunter.controlplane.store.audit.AuditEntry.Kind;
import com.debthunter.controlplane.store.audit.AuditEntry.Outcome;
import com.debthunter.controlplane.store.audit.AuditTrail;
import com.debthunter.controlplane.store.audit.InMemoryAuditTrail;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrailAdminAuditLogTest {

  private static final Instant T = Instant.parse("2026-07-01T12:00:00Z");

  @Test
  void anEntryIsKeptAsAnAllowedAdminLine() {
    InMemoryAuditTrail trail = new InMemoryAuditTrail();

    new TrailAdminAuditLog(trail)
        .record(new AdminAuditEntry("admin", "GRANT_WORKLOAD", "workload=w repository=r", T));

    assertThat(trail.read(Kind.ADMIN, Instant.EPOCH, 10))
        .containsExactly(
            new AuditEntry(
                Kind.ADMIN,
                T,
                "admin",
                "GRANT_WORKLOAD",
                "workload=w repository=r",
                "",
                Outcome.ALLOWED));
  }

  @Test
  void ifTheTrailCannotBeWrittenTheFailureReachesTheCaller() {
    AuditTrail down =
        new AuditTrail() {
          @Override
          public void record(AuditEntry entry) {
            throw new IllegalStateException("down");
          }

          @Override
          public List<AuditEntry> read(Kind kind, Instant from, int limit) {
            return List.of();
          }
        };

    assertThatThrownBy(
            () -> new TrailAdminAuditLog(down).record(new AdminAuditEntry("a", "b", "c", T)))
        .isInstanceOf(IllegalStateException.class);
  }
}
