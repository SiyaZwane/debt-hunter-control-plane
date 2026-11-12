package com.debthunter.controlplane.serve.suppression;

import com.debthunter.controlplane.store.suppressions.Suppression;
import java.time.Instant;

/**
 * A suppression as a squad sees it.
 *
 * @param id its id, for withdrawing it
 * @param repository the repository
 * @param fingerprint the finding
 * @param reason why
 * @param createdAt when it was granted
 * @param expiresAt when it lapses
 */
public record SuppressionView(
    String id,
    String repository,
    String fingerprint,
    String reason,
    Instant createdAt,
    Instant expiresAt) {

  static SuppressionView of(Suppression s) {
    return new SuppressionView(
        s.id(), s.repository(), s.fingerprint(), s.reason(), s.createdAt(), s.expiresAt());
  }
}
