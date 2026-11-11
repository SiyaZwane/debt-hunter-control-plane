package com.debthunter.controlplane.api.ingestion;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A sliding-window limiter tracked independently per caller. Takes an injectable {@link Clock} so
 * tests advance time rather than sleeping.
 */
public final class SlidingWindowRequestRateLimiter implements RequestRateLimiter {

  private final int maxRequests;
  private final Duration window;
  private final Clock clock;
  private final Map<String, Deque<Instant>> requestsByKey = new HashMap<>();

  /**
   * Creates a limiter allowing at most {@code maxRequests} per caller in any trailing {@code
   * window}.
   *
   * @param maxRequests the most requests one caller may make per window
   * @param window the trailing duration requests are counted over
   * @param clock the clock requests are timestamped against
   */
  public SlidingWindowRequestRateLimiter(int maxRequests, Duration window, Clock clock) {
    if (maxRequests <= 0) {
      throw new IllegalArgumentException("maxRequests must be positive");
    }
    this.maxRequests = maxRequests;
    this.window = Objects.requireNonNull(window, "window");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public synchronized RateLimitDecision tryAcquire(String key) {
    Instant now = clock.instant();
    Deque<Instant> requests = requestsByKey.computeIfAbsent(key, ignored -> new ArrayDeque<>());
    Instant windowStart = now.minus(window);
    while (!requests.isEmpty() && !requests.peekFirst().isAfter(windowStart)) {
      requests.pollFirst();
    }
    if (requests.size() >= maxRequests) {
      Duration untilOldestExpires = Duration.between(now, requests.peekFirst().plus(window));
      return RateLimitDecision.refuse(untilOldestExpires);
    }
    requests.addLast(now);
    return RateLimitDecision.permit();
  }
}
