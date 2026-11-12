package com.debthunter.controlplane.store.audit;

import java.time.Instant;

/**
 * Removes audit entries that have outlived their retention. Used only through {@link
 * AuditRetention}, which refuses to purge anything younger than the minimum.
 */
public interface AuditPurger {

  /**
   * Removes entries older than a cut-off.
   *
   * @param cutoff entries before this instant are removed
   * @return how many were removed
   */
  int deleteBefore(Instant cutoff);
}
