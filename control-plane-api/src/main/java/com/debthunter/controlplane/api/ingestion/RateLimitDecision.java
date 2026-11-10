package com.debthunter.controlplane.api.ingestion;

import java.time.Duration;

/**
 * Whether a request may proceed under a rate limit.
 *
 * @param allowed {@code true} if the request is within the limit
 * @param retryAfter how long until a refused caller could succeed, or {@link Duration#ZERO}
 */
public record RateLimitDecision(boolean allowed, Duration retryAfter) {

  /**
   * Within the limit.
   *
   * @return an allowing decision
   */
  public static RateLimitDecision permit() {
    return new RateLimitDecision(true, Duration.ZERO);
  }

  /**
   * Over the limit.
   *
   * @param retryAfter how long until the caller could succeed
   * @return a refusing decision
   */
  public static RateLimitDecision refuse(Duration retryAfter) {
    return new RateLimitDecision(false, retryAfter);
  }
}
