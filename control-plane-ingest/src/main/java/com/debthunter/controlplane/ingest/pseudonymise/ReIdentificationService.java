package com.debthunter.controlplane.ingest.pseudonymise;

import com.debthunter.controlplane.ingest.pseudonymise.ReIdentificationAuditEntry.Outcome;
import com.debthunter.controlplane.store.identity.PseudonymDirectory;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;

/**
 * The one permissioned, audited path from a pseudonym back to a person.
 *
 * <p>A caller needs the {@value #AUTHORITY} authority and must state a justification. Every attempt
 * — refused ones included — is written to the audit log <em>before</em> anything is disclosed, and
 * if the audit write fails the identity is not returned.
 */
public final class ReIdentificationService {

  /** The authority a caller must hold. */
  public static final String AUTHORITY = "REIDENTIFICATION";

  private final PseudonymDirectory directory;
  private final ReIdentificationAuditLog audit;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param directory where identities are kept
   * @param audit where attempts are recorded
   * @param clock the source of audit timestamps
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public ReIdentificationService(
      PseudonymDirectory directory, ReIdentificationAuditLog audit, Clock clock) {
    this.directory = Objects.requireNonNull(directory, "directory");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Reveals who a pseudonym stands for.
   *
   * @param request who is asking, about whom, and why
   * @return the identity, or empty if the pseudonym is unknown
   * @throws ReIdentificationDeniedException if the caller lacks the authority or gave no
   *     justification
   */
  public Optional<String> reIdentify(ReIdentificationRequest request) {
    boolean authorised = request.authorities().contains(AUTHORITY);
    boolean justified = request.justification() != null && !request.justification().isBlank();
    Outcome outcome = authorised && justified ? Outcome.GRANTED : Outcome.DENIED;

    audit.record(
        new ReIdentificationAuditEntry(
            request.caller(),
            request.pseudonym(),
            request.justification(),
            clock.instant(),
            outcome));

    if (outcome == Outcome.DENIED) {
      throw new ReIdentificationDeniedException(
          authorised
              ? "A justification is required"
              : "The " + AUTHORITY + " authority is required");
    }
    return directory.lookup(request.pseudonym());
  }
}
