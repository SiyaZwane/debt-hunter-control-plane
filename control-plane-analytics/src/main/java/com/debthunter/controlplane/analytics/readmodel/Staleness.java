package com.debthunter.controlplane.analytics.readmodel;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides whether the read models are stale: last updated longer ago than a threshold, or never.
 */
public final class Staleness {

  private final ReadModelRepository readModel;
  private final Clock clock;
  private final Duration threshold;

  /**
   * Creates the check.
   *
   * @param readModel the read models
   * @param clock the source of "now"
   * @param threshold how old {@code lastUpdated} may be before results are marked stale
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public Staleness(ReadModelRepository readModel, Clock clock, Duration threshold) {
    this.readModel = Objects.requireNonNull(readModel, "readModel");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.threshold = Objects.requireNonNull(threshold, "threshold");
    if (threshold.isNegative() || threshold.isZero()) {
      throw new IllegalArgumentException("The staleness threshold must be positive");
    }
  }

  /**
   * Wraps a result with the read models' freshness.
   *
   * @param value the result
   * @param <T> its type
   * @return the result with its {@code stale} flag and {@code lastUpdated}
   */
  public <T> Trended<T> wrap(T value) {
    Optional<Instant> last = readModel.lastUpdated();
    boolean stale =
        last.isEmpty() || Duration.between(last.get(), clock.instant()).compareTo(threshold) > 0;
    return new Trended<>(value, stale, last.orElse(null));
  }
}
