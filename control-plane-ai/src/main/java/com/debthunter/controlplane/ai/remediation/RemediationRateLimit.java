package com.debthunter.controlplane.ai.remediation;

import java.time.Duration;
import java.util.Objects;

/**
 * How many remediation pull requests may be opened for one repository in a window, so a burst of
 * findings cannot flood it. Requests over the limit are not refused or dropped: they wait on the
 * queue and are looked at again every {@code recheck}.
 *
 * @param maxPerWindow the most pull requests per repository per window
 * @param window the trailing window they are counted over
 * @param recheck how long a request that is over the limit waits before being looked at again
 */
public record RemediationRateLimit(int maxPerWindow, Duration window, Duration recheck) {

  /** Validates the components. */
  public RemediationRateLimit {
    if (maxPerWindow < 1) {
      throw new IllegalArgumentException("maxPerWindow must be at least 1");
    }
    Objects.requireNonNull(window, "window");
    Objects.requireNonNull(recheck, "recheck");
    if (window.isZero() || window.isNegative() || recheck.isZero() || recheck.isNegative()) {
      throw new IllegalArgumentException("window and recheck must be positive");
    }
  }
}
