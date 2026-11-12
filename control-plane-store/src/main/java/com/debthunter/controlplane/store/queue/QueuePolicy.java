package com.debthunter.controlplane.store.queue;

import java.time.Duration;
import java.util.Objects;

/**
 * How one kind of work is queued and retried.
 *
 * @param ordered whether items with the same key must be handled one at a time, oldest first. When
 *     true, a repository's next item waits for its earlier one to finish, whatever happens to other
 *     keys
 * @param maxAttempts how many times an item may be tried before it is set aside as dead
 * @param lease how long a worker may hold an item before it is presumed lost and handed out again
 * @param initialBackoff the wait after the first failed attempt
 * @param maxBackoff the longest wait between attempts
 */
public record QueuePolicy(
    boolean ordered,
    int maxAttempts,
    Duration lease,
    Duration initialBackoff,
    Duration maxBackoff) {

  /** Validates the components. */
  public QueuePolicy {
    if (maxAttempts < 1) {
      throw new IllegalArgumentException("maxAttempts must be at least 1");
    }
    Objects.requireNonNull(lease, "lease");
    Objects.requireNonNull(initialBackoff, "initialBackoff");
    Objects.requireNonNull(maxBackoff, "maxBackoff");
    if (lease.isZero() || lease.isNegative()) {
      throw new IllegalArgumentException("lease must be positive");
    }
    if (initialBackoff.isNegative() || maxBackoff.compareTo(initialBackoff) < 0) {
      throw new IllegalArgumentException("backoff must be non-negative and max >= initial");
    }
  }

  /** The wait before the next attempt, after {@code attempts} attempts have failed. */
  public Duration backoffAfter(int attempts) {
    Duration wait = initialBackoff;
    for (int i = 1; i < attempts && wait.compareTo(maxBackoff) < 0; i++) {
      wait = wait.multipliedBy(2);
    }
    return wait.compareTo(maxBackoff) > 0 ? maxBackoff : wait;
  }
}
