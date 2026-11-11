package com.debthunter.controlplane.ingest.lifecycle;

import java.time.Instant;
import java.util.Objects;

/**
 * A suppression on a finding.
 *
 * @param expiresAt when it stops applying, compared against the commit date and never the wall
 *     clock
 */
public record ActiveSuppression(Instant expiresAt) {

  /** Validates the component. */
  public ActiveSuppression {
    Objects.requireNonNull(expiresAt, "expiresAt");
  }

  /**
   * Whether the suppression applies at a point in the code's history.
   *
   * @param commitDate the commit date being evaluated
   * @return true if it has not yet expired
   */
  public boolean appliesAt(Instant commitDate) {
    return commitDate.isBefore(expiresAt);
  }
}
