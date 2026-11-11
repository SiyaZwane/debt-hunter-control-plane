package com.debthunter.controlplane.store.suppressions;

import java.util.Optional;

/**
 * Read access to the suppression register. The register itself (creating, approving and expiring
 * suppressions) arrives with the system of record; the lifecycle only needs to ask.
 */
public interface SuppressionLookup {

  /**
   * Finds the suppression on a finding.
   *
   * @param repository the repository
   * @param fingerprint the finding's fingerprint
   * @return its suppression, or empty if it has none
   */
  Optional<ActiveSuppression> find(String repository, String fingerprint);
}
