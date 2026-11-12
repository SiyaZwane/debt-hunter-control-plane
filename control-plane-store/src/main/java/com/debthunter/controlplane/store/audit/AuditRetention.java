package com.debthunter.controlplane.store.audit;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * How long the audit trail is kept. Entries are never removed before {@link #MINIMUM}, however the
 * retention is configured: a trail an auditor cannot rely on being complete for ninety days is not
 * an audit trail.
 */
public final class AuditRetention {

  /** The shortest retention allowed. */
  public static final Duration MINIMUM = Duration.ofDays(90);

  private final AuditPurger purger;
  private final Duration retention;
  private final Clock clock;

  /**
   * Creates the retention policy.
   *
   * @param purger removes old entries
   * @param retention how long to keep them; at least {@link #MINIMUM}
   * @param clock the source of "now"
   * @throws IllegalArgumentException if the retention is shorter than the minimum
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public AuditRetention(AuditPurger purger, Duration retention, Clock clock) {
    this.purger = Objects.requireNonNull(purger, "purger");
    this.retention = Objects.requireNonNull(retention, "retention");
    this.clock = Objects.requireNonNull(clock, "clock");
    if (retention.compareTo(MINIMUM) < 0) {
      throw new IllegalArgumentException(
          "Audit entries must be kept for at least " + MINIMUM.toDays() + " days");
    }
  }

  /**
   * Removes the entries that have outlived the retention.
   *
   * @return how many were removed
   */
  public int purge() {
    return purger.deleteBefore(clock.instant().minus(retention));
  }
}
