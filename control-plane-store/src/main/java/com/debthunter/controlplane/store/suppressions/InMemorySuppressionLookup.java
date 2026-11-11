package com.debthunter.controlplane.store.suppressions;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A {@link SuppressionLookup} held in memory, for tests and local development. */
public final class InMemorySuppressionLookup implements SuppressionLookup {

  private final Map<String, ActiveSuppression> suppressions = new ConcurrentHashMap<>();

  /**
   * Suppresses a finding until a date.
   *
   * @param repository the repository
   * @param fingerprint the finding's fingerprint
   * @param expiresAt when the suppression stops applying
   * @return this, for chaining
   */
  public InMemorySuppressionLookup suppress(
      String repository, String fingerprint, Instant expiresAt) {
    suppressions.put(repository + '\u0000' + fingerprint, new ActiveSuppression(expiresAt));
    return this;
  }

  @Override
  public Optional<ActiveSuppression> find(String repository, String fingerprint) {
    return Optional.ofNullable(suppressions.get(repository + '\u0000' + fingerprint));
  }
}
