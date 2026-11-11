package com.debthunter.controlplane.analytics.coverage;

import java.time.Duration;
import java.util.Objects;

/**
 * What "silent", "degraded" and "drifted" mean.
 *
 * @param cadence how long a repository may go without a run being received before it is silent
 * @param currentToolMajor the data-plane major version that counts as current
 * @param degradedWindow how many of a repository's latest runs must all be degraded or shallow for
 *     it to be consistently so; with fewer runs than this it cannot be called consistent
 */
public record CoverageSettings(Duration cadence, int currentToolMajor, int degradedWindow) {

  /** Validates the settings. */
  public CoverageSettings {
    Objects.requireNonNull(cadence, "cadence");
    if (cadence.isNegative() || cadence.isZero()) {
      throw new IllegalArgumentException("The cadence window must be positive");
    }
    if (currentToolMajor < 0) {
      throw new IllegalArgumentException("The current tool major version must not be negative");
    }
    if (degradedWindow < 1) {
      throw new IllegalArgumentException("The degraded window must be at least 1");
    }
  }
}
