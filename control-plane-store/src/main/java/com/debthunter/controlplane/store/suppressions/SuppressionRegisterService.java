package com.debthunter.controlplane.store.suppressions;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The suppression register: grants and revokes suppressions, answers whether a finding is
 * suppressed, and warns owners before a suppression lapses.
 *
 * <p>Everything here is judged against a <em>commit date</em> the caller supplies, never the wall
 * clock, so replaying history gives the same answers it gave at the time.
 */
public final class SuppressionRegisterService implements SuppressionLookup {

  private final SuppressionRepository suppressions;
  private final SuppressionEventPublisher events;

  /**
   * Creates the service.
   *
   * @param suppressions where suppressions are kept
   * @param events where pre-expiry warnings go
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public SuppressionRegisterService(
      SuppressionRepository suppressions, SuppressionEventPublisher events) {
    this.suppressions = Objects.requireNonNull(suppressions, "suppressions");
    this.events = Objects.requireNonNull(events, "events");
  }

  /**
   * Suppresses a finding.
   *
   * @param repository the repository
   * @param fingerprint the finding
   * @param reason why; required
   * @param createdAt when the suppression is granted
   * @param expiresAt when it lapses; at most {@code maxExpiryDays} after {@code createdAt}
   * @param maxExpiryDays the policy cap on a suppression's life
   * @return the suppression
   * @throws IllegalArgumentException if it has no reason or outlives its cap
   */
  public Suppression suppress(
      String repository,
      String fingerprint,
      String reason,
      Instant createdAt,
      Instant expiresAt,
      int maxExpiryDays) {
    Suppression suppression =
        new Suppression(
            UUID.randomUUID().toString(),
            repository,
            fingerprint,
            reason,
            createdAt,
            expiresAt,
            maxExpiryDays,
            null);
    suppressions.save(suppression);
    return suppression;
  }

  /**
   * Looks up a suppression by its id.
   *
   * @param id the suppression's id
   * @return the suppression, if there is one
   */
  public Optional<Suppression> findById(String id) {
    return suppressions.findById(id);
  }

  /**
   * Withdraws a suppression.
   *
   * @param id the suppression's id
   * @return whether it existed
   */
  public boolean revoke(String id) {
    return suppressions.delete(id);
  }

  /**
   * Whether a finding is suppressed as of a commit date.
   *
   * @param repository the repository
   * @param fingerprint the finding
   * @param commitDate the point in history being evaluated
   * @return true if a suppression exists and has not yet lapsed at that date
   */
  public boolean isSuppressed(String repository, String fingerprint, Instant commitDate) {
    return find(repository, fingerprint).map(s -> s.appliesAt(commitDate)).orElse(false);
  }

  @Override
  public Optional<ActiveSuppression> find(String repository, String fingerprint) {
    return suppressions
        .find(repository, fingerprint)
        .map(s -> new ActiveSuppression(s.expiresAt()));
  }

  /**
   * Warns the owner of every suppression that is due to lapse, once each.
   *
   * <p>A suppression is warned about when {@code asOf} reaches its {@link Suppression#warnAt()},
   * and only while it is still in force. Each is marked after its event is published, so a failed
   * publish is retried on the next sweep, and a crash between the two can at worst repeat a warning
   * (consumers must tolerate that) but never lose one.
   *
   * @param asOf the commit date to evaluate against
   * @return how many warnings were raised
   */
  public int emitPreExpiryEvents(Instant asOf) {
    int raised = 0;
    for (Suppression s : suppressions.findUnnotified()) {
      if (!asOf.isBefore(s.warnAt()) && asOf.isBefore(s.expiresAt())) {
        events.publish(
            new SuppressionPreExpiryEvent(
                s.id(), s.repository(), s.fingerprint(), s.expiresAt(), asOf));
        suppressions.markNotified(s.id(), asOf);
        raised++;
      }
    }
    return raised;
  }
}
