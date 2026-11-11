package com.debthunter.controlplane.api.ingestion;

/**
 * Limits how fast one caller may submit bundles, so a burst from one pipeline cannot starve others.
 */
public interface RequestRateLimiter {

  /**
   * Counts a request against a caller's limit.
   *
   * @param key who is asking — the authenticated workload identity
   * @return whether the request may proceed, and if not, when to retry
   */
  RateLimitDecision tryAcquire(String key);
}
