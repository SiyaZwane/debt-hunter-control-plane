package com.debthunter.controlplane.api.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SlidingWindowRequestRateLimiterTest {

  private final TestClock clock = new TestClock(Instant.parse("2026-01-01T00:00:00Z"));
  private final RequestRateLimiter limiter =
      new SlidingWindowRequestRateLimiter(3, Duration.ofMinutes(1), clock);

  @Test
  void requestsUpToTheLimitAreAllowed() {
    assertThat(limiter.tryAcquire("svc-a").allowed()).isTrue();
    assertThat(limiter.tryAcquire("svc-a").allowed()).isTrue();
    assertThat(limiter.tryAcquire("svc-a").allowed()).isTrue();
  }

  @Test
  void theRequestOverTheLimitIsRefusedWithAWaitTime() {
    for (int i = 0; i < 3; i++) {
      limiter.tryAcquire("svc-a");
    }
    clock.advance(Duration.ofSeconds(20));

    RateLimitDecision decision = limiter.tryAcquire("svc-a");

    assertThat(decision.allowed()).isFalse();
    assertThat(decision.retryAfter()).isEqualTo(Duration.ofSeconds(40));
  }

  @Test
  void theLimitRecoversOnceTheWindowHasPassed() {
    for (int i = 0; i < 3; i++) {
      limiter.tryAcquire("svc-a");
    }
    clock.advance(Duration.ofMinutes(1));

    assertThat(limiter.tryAcquire("svc-a").allowed()).isTrue();
  }

  @Test
  void eachCallerHasItsOwnLimit() {
    for (int i = 0; i < 3; i++) {
      limiter.tryAcquire("svc-a");
    }

    assertThat(limiter.tryAcquire("svc-a").allowed()).isFalse();
    assertThat(limiter.tryAcquire("svc-b").allowed()).isTrue();
  }

  @Test
  void aNonPositiveLimitIsRejected() {
    assertThatThrownBy(() -> new SlidingWindowRequestRateLimiter(0, Duration.ofMinutes(1), clock))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
