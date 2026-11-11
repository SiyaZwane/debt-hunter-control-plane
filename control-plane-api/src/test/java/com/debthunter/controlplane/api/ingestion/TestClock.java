package com.debthunter.controlplane.api.ingestion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock tests can advance, so time-dependent behaviour never needs a real sleep. */
public final class TestClock extends Clock {

  private Instant instant;

  /**
   * Creates a clock fixed at a starting instant.
   *
   * @param instant where the clock starts
   */
  public TestClock(Instant instant) {
    this.instant = instant;
  }

  /**
   * Moves the clock forward.
   *
   * @param duration how far to advance
   */
  public synchronized void advance(Duration duration) {
    instant = instant.plus(duration);
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    throw new UnsupportedOperationException();
  }

  @Override
  public synchronized Instant instant() {
    return instant;
  }
}
