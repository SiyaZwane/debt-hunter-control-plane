package com.debthunter.controlplane.serve.audit;

import com.debthunter.controlplane.serve.access.Access;
import com.debthunter.controlplane.serve.access.Caller;
import com.debthunter.controlplane.serve.access.InvalidRequestException;
import com.debthunter.controlplane.serve.access.Roles;
import com.debthunter.controlplane.serve.access.ServiceUnavailableException;
import com.debthunter.controlplane.store.audit.AuditEntry;
import com.debthunter.controlplane.store.audit.AuditTrail;
import com.debthunter.controlplane.store.evidence.EvidenceAccess;
import com.debthunter.controlplane.store.evidence.EvidenceAccessLog;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Lets an auditor read the audit trails. Only the {@value Roles#AUDITOR} role may, and that role is
 * separate from every role that can make the changes being audited, so nobody audits themselves.
 */
public final class AuditReadService {

  /** The most entries returned in one page. */
  public static final int MAX_PAGE = 500;

  /** The page size used when none is asked for. */
  public static final int DEFAULT_PAGE = 100;

  /** The trails an auditor can read. */
  public enum Trail {
    /** Changes to who the control plane serves. */
    ADMIN,
    /** Requests to turn a pseudonym back into a person. */
    REIDENTIFICATION,
    /** Suppressions granted or withdrawn by squads. */
    SUPPRESSION,
    /** Retrievals of a run's evidence. */
    EVIDENCE
  }

  private final AuditTrail trail;
  private final EvidenceAccessLog evidence;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param trail the durable trail
   * @param evidence the evidence access log
   * @param clock the source of "now"
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public AuditReadService(AuditTrail trail, EvidenceAccessLog evidence, Clock clock) {
    this.trail = Objects.requireNonNull(trail, "trail");
    this.evidence = Objects.requireNonNull(evidence, "evidence");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Reads a page of a trail.
   *
   * @param caller who is asking
   * @param which which trail
   * @param from the earliest time wanted, or null for the beginning
   * @param limit the page size, or null for the default; at most {@value #MAX_PAGE}
   * @return the page
   * @throws com.debthunter.controlplane.serve.access.AccessDeniedException if the caller is not an
   *     auditor
   * @throws InvalidRequestException if the limit is not usable
   * @throws ServiceUnavailableException if the trail could not be read
   */
  public AuditPage read(Caller caller, Trail which, Instant from, Integer limit) {
    Access.requireRole(caller, Roles.AUDITOR);
    Objects.requireNonNull(which, "which");
    int size = limit == null ? DEFAULT_PAGE : limit;
    if (size < 1 || size > MAX_PAGE) {
      throw new InvalidRequestException("limit must be between 1 and " + MAX_PAGE);
    }
    Instant start = from == null ? Instant.EPOCH : from;
    List<AuditPage.AuditLine> lines;
    try {
      lines = which == Trail.EVIDENCE ? evidenceLines(start, size) : trailLines(which, start, size);
    } catch (RuntimeException e) {
      throw new ServiceUnavailableException("The audit trail could not be read");
    }
    Instant next = lines.size() == size ? lines.get(lines.size() - 1).at() : null;
    return new AuditPage(which, lines, next, clock.instant());
  }

  private List<AuditPage.AuditLine> trailLines(Trail which, Instant from, int size) {
    AuditEntry.Kind kind = AuditEntry.Kind.valueOf(which.name());
    return trail.read(kind, from, size).stream()
        .map(
            e ->
                new AuditPage.AuditLine(
                    e.at(), e.caller(), e.action(), e.target(), e.detail(), e.outcome().name()))
        .toList();
  }

  private List<AuditPage.AuditLine> evidenceLines(Instant from, int size) {
    return evidence.since(from, size).stream()
        .map(
            (EvidenceAccess a) ->
                new AuditPage.AuditLine(
                    a.at(),
                    a.caller(),
                    "RETRIEVE_EVIDENCE",
                    a.runId() + (a.repository() == null ? "" : " " + a.repository()),
                    a.purpose(),
                    a.outcome().name()))
        .toList();
  }
}
