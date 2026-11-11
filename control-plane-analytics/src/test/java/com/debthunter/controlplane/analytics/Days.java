package com.debthunter.controlplane.analytics;

import com.debthunter.controlplane.testkit.RunBundleBuilder;
import java.time.Duration;
import java.time.Instant;

/** Builds runs placed on a calendar, so tests can say "on day 3" and mean it. */
public final class Days {

  public static final String REPO = "acme/widgets";
  public static final Instant DAY0 = Instant.parse("2026-01-01T00:00:00Z");

  private Days() {}

  public static Instant day(int n) {
    return DAY0.plus(Duration.ofDays(n));
  }

  /** A run on a given day, with the commit and run id derived from the day. */
  public static RunBundleBuilder run(int day) {
    return RunBundleBuilder.run()
        .id("run-day-" + day)
        .repository(REPO)
        .commit("c" + day)
        .timestamp(day(day).toString());
  }
}
