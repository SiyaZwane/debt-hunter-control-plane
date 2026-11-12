package com.debthunter.controlplane.serve.suppression;

import com.debthunter.controlplane.serve.access.Access;
import com.debthunter.controlplane.serve.access.AccessDeniedException;
import com.debthunter.controlplane.serve.access.Caller;
import com.debthunter.controlplane.serve.access.InvalidRequestException;
import com.debthunter.controlplane.serve.access.NotFoundException;
import com.debthunter.controlplane.serve.access.Roles;
import com.debthunter.controlplane.serve.access.ServiceUnavailableException;
import com.debthunter.controlplane.store.audit.AuditEntry;
import com.debthunter.controlplane.store.audit.AuditTrail;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistry;
import com.debthunter.controlplane.store.suppressions.Suppression;
import com.debthunter.controlplane.store.suppressions.SuppressionRegisterService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lets a squad set aside a finding in one of its own repositories, for a stated reason, until a
 * date no later than the policy allows — and withdraw it again.
 *
 * <p>A squad may act only on repositories it owns, whatever the request says. A grant or withdrawal
 * is recorded in the audit trail before it is applied and is not applied if it cannot be recorded;
 * a refused attempt is recorded too, as far as it can be, because a squad reaching for another
 * squad's findings is worth knowing about.
 */
public final class SquadSuppressionService {

  private static final Logger LOG = LoggerFactory.getLogger(SquadSuppressionService.class);

  /** The longest reason kept. */
  public static final int MAX_REASON = 500;

  /** The shortest reason accepted: an unexplained suppression is just a hidden finding. */
  public static final int MIN_REASON = 10;

  private static final int MAX_FINGERPRINT = 200;

  private final RepositoryRegistry registry;
  private final SuppressionRegisterService suppressions;
  private final AuditTrail audit;
  private final int maxExpiryDays;
  private final Clock clock;

  /**
   * Creates the service.
   *
   * @param registry which squad owns which repository
   * @param suppressions the suppression register
   * @param audit where grants, withdrawals and refusals are recorded
   * @param maxExpiryDays the policy cap on a suppression's life, in days
   * @param clock the source of "now"
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public SquadSuppressionService(
      RepositoryRegistry registry,
      SuppressionRegisterService suppressions,
      AuditTrail audit,
      int maxExpiryDays,
      Clock clock) {
    if (maxExpiryDays <= 0) {
      throw new IllegalArgumentException("maxExpiryDays must be positive");
    }
    this.registry = Objects.requireNonNull(registry, "registry");
    this.suppressions = Objects.requireNonNull(suppressions, "suppressions");
    this.audit = Objects.requireNonNull(audit, "audit");
    this.maxExpiryDays = maxExpiryDays;
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Suppresses a finding of one of the squad's repositories.
   *
   * @param caller who is asking
   * @param squad the squad acting
   * @param request what to suppress, why and until when
   * @return the suppression
   * @throws AccessDeniedException if the caller lacks the squad role, is not in the squad, or the
   *     repository is not the squad's (an unknown repository is refused the same way)
   * @throws InvalidRequestException if the request is incomplete, the reason too short, or the
   *     expiry not in the future or beyond the policy cap
   * @throws ServiceUnavailableException if the grant could not be recorded, in which case nothing
   *     was granted
   */
  public SuppressionView suppress(Caller caller, String squad, SuppressRequest request) {
    Access.requireRole(caller, Roles.SQUAD);
    Objects.requireNonNull(request, "request");
    String repository = required("repository", request.repository(), 300);
    String fingerprint = required("fingerprint", request.fingerprint(), MAX_FINGERPRINT);
    String target = repository + " " + fingerprint;
    if (!owns(caller, squad, repository)) {
      refuse(caller, "SUPPRESS", target, request.reason());
      throw new AccessDeniedException("That repository does not belong to the squad");
    }
    String reason = reason(request.reason());
    Instant now = clock.instant();
    Instant expiresAt = expiry(request.expiresAt(), now);

    record(caller, "SUPPRESS", target, reason, AuditEntry.Outcome.ALLOWED, now);
    Suppression made =
        suppressions.suppress(repository, fingerprint, reason, now, expiresAt, maxExpiryDays);
    return SuppressionView.of(made);
  }

  /**
   * Withdraws a suppression on one of the squad's repositories.
   *
   * @param caller who is asking
   * @param squad the squad acting
   * @param id the suppression's id
   * @throws AccessDeniedException if the caller lacks the squad role or is not in the squad
   * @throws NotFoundException if there is no such suppression, or it is not the squad's
   * @throws ServiceUnavailableException if the withdrawal could not be recorded, in which case
   *     nothing was withdrawn
   */
  public void revoke(Caller caller, String squad, String id) {
    Access.requireRole(caller, Roles.SQUAD);
    if (squad == null || !caller.isIn(squad)) {
      refuse(caller, "REVOKE_SUPPRESSION", id, "");
      throw new AccessDeniedException("The caller does not belong to that squad");
    }
    Optional<Suppression> found = suppressions.findById(Objects.requireNonNull(id, "id"));
    if (found.isEmpty() || !owns(caller, squad, found.get().repository())) {
      refuse(
          caller,
          "REVOKE_SUPPRESSION",
          found.map(s -> s.repository() + " " + s.fingerprint()).orElse(id),
          "");
      throw new NotFoundException("There is no such suppression");
    }
    Suppression s = found.get();
    record(
        caller,
        "REVOKE_SUPPRESSION",
        s.repository() + " " + s.fingerprint(),
        "",
        AuditEntry.Outcome.ALLOWED,
        clock.instant());
    suppressions.revoke(id);
  }

  private boolean owns(Caller caller, String squad, String repository) {
    if (squad == null || !caller.isIn(squad)) {
      return false;
    }
    return registry
        .find(repository)
        .map(OnboardedRepository::owner)
        .filter(squad::equals)
        .isPresent();
  }

  private static String required(String name, String value, int max) {
    if (value == null || value.isBlank()) {
      throw new InvalidRequestException(name + " is required");
    }
    if (value.length() > max) {
      throw new InvalidRequestException(name + " is too long");
    }
    return value.strip();
  }

  private static String reason(String value) {
    if (value == null || value.strip().length() < MIN_REASON) {
      throw new InvalidRequestException(
          "A reason of at least " + MIN_REASON + " characters is required");
    }
    String reason = value.strip();
    return reason.length() <= MAX_REASON ? reason : reason.substring(0, MAX_REASON);
  }

  private Instant expiry(Instant requested, Instant now) {
    if (requested == null) {
      throw new InvalidRequestException("expiresAt is required");
    }
    if (!requested.isAfter(now)) {
      throw new InvalidRequestException("expiresAt must be in the future");
    }
    if (requested.isAfter(now.plus(Duration.ofDays(maxExpiryDays)))) {
      throw new InvalidRequestException(
          "A suppression may not run longer than " + maxExpiryDays + " days");
    }
    return requested;
  }

  private void record(
      Caller caller,
      String action,
      String target,
      String detail,
      AuditEntry.Outcome outcome,
      Instant at) {
    try {
      audit.record(
          new AuditEntry(
              AuditEntry.Kind.SUPPRESSION, at, caller.subject(), action, target, detail, outcome));
    } catch (RuntimeException e) {
      LOG.error("a suppression could not be recorded, so it was not applied", e);
      throw new ServiceUnavailableException("The change could not be recorded");
    }
  }

  /** A refusal is recorded if it can be; failing to record it does not turn it into an approval. */
  private void refuse(Caller caller, String action, String target, String detail) {
    try {
      audit.record(
          new AuditEntry(
              AuditEntry.Kind.SUPPRESSION,
              clock.instant(),
              caller.subject(),
              action,
              target,
              detail,
              AuditEntry.Outcome.DENIED));
    } catch (RuntimeException e) {
      LOG.warn("a refused suppression attempt could not be recorded", e);
    }
  }
}
