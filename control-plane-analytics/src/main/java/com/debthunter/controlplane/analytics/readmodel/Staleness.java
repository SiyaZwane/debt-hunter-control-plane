package com.debthunter.controlplane.analytics.readmodel;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Decides whether the read models are stale: last updated longer ago than a threshold, or never.
 */
public final class Staleness {

  private final Supplier<Optional<Instant>> lastUpdated;
  private final Clock clock;
  private final Duration threshold;

  /**
   * Creates the check.
   *
   * @param readModel the read models
   * @param clock the source of "now"
   * @param threshold how old {@code lastUpdated} may be before results are marked stale
   */
  public Staleness(ReadModelRepository readModel, Clock clock, Duration threshold) {
    this(Objects.requireNonNull(readModel, "readModel")::lastUpdated, clock, threshold);
  }

  /**
   * Creates the check over any source of "when was this last brought up to date", such as the
   * coverage reconciliation, which runs on its own schedule.
   *
   * @param lastUpdated when the data behind a result was last refreshed; empty if never
   * @param clock the source of "now"
   * @param threshold how old that may be before results are marked stale
   */
  public Staleness(Supplier<Optional<Instant>> lastUpdated, Clock clock, Duration threshold) {
    this.lastUpdated = Objects.requireNonNull(lastUpdated, "lastUpdated");
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
    Optional<Instant> last = lastUpdated.get();
    boolean stale =
        last.isEmpty() || Duration.between(last.get(), clock.instant()).compareTo(threshold) > 0;
    return new Trended<>(value, stale, last.orElse(null));
  }
}
