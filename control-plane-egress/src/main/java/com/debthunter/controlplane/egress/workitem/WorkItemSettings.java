package com.debthunter.controlplane.egress.workitem;

import com.debthunter.domain.Severity;
import java.time.Duration;
import java.util.Objects;

/**
 * How work items are decided.
 *
 * @param minimumSeverity the least severe finding that gets an item; items are created for this
 *     severity and anything more severe
 * @param claimTtl how long a worker may hold the right to write for one finding before the right is
 *     presumed lost
 */
public record WorkItemSettings(Severity minimumSeverity, Duration claimTtl) {

  /** Validates the components. */
  public WorkItemSettings {
    Objects.requireNonNull(minimumSeverity, "minimumSeverity");
    Objects.requireNonNull(claimTtl, "claimTtl");
    if (claimTtl.isZero() || claimTtl.isNegative()) {
      throw new IllegalArgumentException("claimTtl must be positive");
    }
  }

  /** High and critical findings get items; claims last two minutes. */
  public static WorkItemSettings defaults() {
    return new WorkItemSettings(Severity.HIGH, Duration.ofMinutes(2));
  }

  /** Whether a finding of this severity gets an item. */
  public boolean qualifies(Severity severity) {
    return severity.ordinal() <= minimumSeverity.ordinal();
  }
}
