package com.debthunter.controlplane.api.admin;

import com.debthunter.controlplane.store.audit.AuditEntry;
import com.debthunter.controlplane.store.audit.AuditTrail;
import java.util.Objects;

/**
 * Records administration changes in the durable audit trail, then in the application log.
 *
 * <p>The durable write comes first and its failure propagates, so a change that cannot be recorded
 * is not made; the log line is a convenience on top.
 */
public final class TrailAdminAuditLog implements AdminAuditLog {

  private final AuditTrail trail;
  private final AdminAuditLog log;

  /**
   * Creates the log.
   *
   * @param trail the durable trail
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public TrailAdminAuditLog(AuditTrail trail) {
    this.trail = Objects.requireNonNull(trail, "trail");
    this.log = new LoggingAdminAuditLog();
  }

  @Override
  public void record(AdminAuditEntry entry) {
    trail.record(
        new AuditEntry(
            AuditEntry.Kind.ADMIN,
            entry.at(),
            entry.caller(),
            entry.action(),
            entry.target(),
            "",
            AuditEntry.Outcome.ALLOWED));
    log.record(entry);
  }
}
