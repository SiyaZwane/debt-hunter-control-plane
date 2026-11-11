package com.debthunter.controlplane.store.suppressions;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * A deliberate decision to set a finding aside until a date.
 *
 * @param id the suppression's id
 * @param repository the repository
 * @param fingerprint the finding it covers
 * @param reason why, in words; mandatory, because an unexplained suppression is just a hidden
 *     finding
 * @param createdAt when it was granted
 * @param expiresAt when it stops applying
 * @param maxExpiryDays the longest a suppression of this kind may run, from policy
 * @param preExpiryNotifiedAt when the owner was warned it is about to lapse, or {@code null}
 */
public record Suppression(
    String id,
    String repository,
    String fingerprint,
    String reason,
    Instant createdAt,
    Instant expiresAt,
    int maxExpiryDays,
    Instant preExpiryNotifiedAt) {

  /** How long before the end of its allowed life the owner is warned. */
  public static final Duration WARNING_LEAD = Duration.ofDays(7);

  /** Validates the components, including that the suppression respects its policy cap. */
  public Suppression {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(fingerprint, "fingerprint");
    Objects.requireNonNull(createdAt, "createdAt");
    Objects.requireNonNull(expiresAt, "expiresAt");
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("A suppression needs a reason");
    }
    if (maxExpiryDays <= 0) {
      throw new IllegalArgumentException("maxExpiryDays must be positive");
    }
    if (!expiresAt.isAfter(createdAt)) {
      throw new IllegalArgumentException("A suppression must expire after it is created");
    }
    if (expiresAt.isAfter(createdAt.plus(Duration.ofDays(maxExpiryDays)))) {
      throw new IllegalArgumentException(
          "A suppression may not run longer than " + maxExpiryDays + " days");
    }
  }

  /**
   * When the owner should be warned: seven days before the end of the maximum life ({@code
   * maxExpiryDays - 7}), or seven days before this suppression's own expiry if that comes sooner,
   * so a short suppression is not warned about after it has already lapsed.
   *
   * @return the instant the warning falls due
   */
  public Instant warnAt() {
    Instant atMax = createdAt.plus(Duration.ofDays(maxExpiryDays)).minus(WARNING_LEAD);
    Instant atExpiry = expiresAt.minus(WARNING_LEAD);
    Instant earliest = atMax.isBefore(atExpiry) ? atMax : atExpiry;
    return earliest.isBefore(createdAt) ? createdAt : earliest;
  }

  /** A copy recording that the owner has been warned. */
  public Suppression notified(Instant at) {
    return new Suppression(
        id, repository, fingerprint, reason, createdAt, expiresAt, maxExpiryDays, at);
  }
}
