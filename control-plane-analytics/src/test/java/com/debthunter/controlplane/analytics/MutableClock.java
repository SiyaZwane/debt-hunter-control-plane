package com.debthunter.controlplane.analytics;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/** A clock the test moves by hand. */
public final class MutableClock extends Clock {

  private Instant now;

  public MutableClock(Instant now) {
    this.now = now;
  }

  public void set(Instant instant) {
    this.now = instant;
  }

  @Override
  public ZoneId getZone() {
    return ZoneOffset.UTC;
  }

  @Override
  public Clock withZone(ZoneId zone) {
    return this;
  }

  @Override
  public Instant instant() {
    return now;
  }
}
