package com.debthunter.controlplane.ingest.pseudonymise;

import com.debthunter.controlplane.store.audit.AuditEntry;
import com.debthunter.controlplane.store.audit.AuditTrail;
import java.util.Objects;

/**
 * Records re-identification attempts in the durable audit trail, then in the application log.
 *
 * <p>The durable write comes first and its failure propagates, which is what stops {@link
 * ReIdentificationService} disclosing an identity it could not record. The identity itself is never
 * written, only whom the caller asked about.
 */
public final class TrailReIdentificationAuditLog implements ReIdentificationAuditLog {

  private final AuditTrail trail;
  private final ReIdentificationAuditLog log;

  /**
   * Creates the log.
   *
   * @param trail the durable trail
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public TrailReIdentificationAuditLog(AuditTrail trail) {
    this.trail = Objects.requireNonNull(trail, "trail");
    this.log = new LoggingReIdentificationAuditLog();
  }

  @Override
  public void record(ReIdentificationAuditEntry entry) {
    trail.record(
        new AuditEntry(
            AuditEntry.Kind.REIDENTIFICATION,
            entry.at(),
            entry.caller(),
            "REIDENTIFY",
            entry.pseudonym().toString(),
            entry.justification(),
            entry.outcome() == ReIdentificationAuditEntry.Outcome.GRANTED
                ? AuditEntry.Outcome.ALLOWED
                : AuditEntry.Outcome.DENIED));
    log.record(entry);
  }
}
