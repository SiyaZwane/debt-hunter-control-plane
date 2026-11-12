package com.debthunter.controlplane.egress.digest;

import com.debthunter.controlplane.egress.outbox.TransactionalOutbox;
import com.debthunter.controlplane.store.queue.QueuePolicy;
import java.time.Duration;
import java.util.Map;

/** What the digest queue is: one kind of message, ordered per squad. */
public final class DigestQueue {

  /** The kind of message in the queue. */
  public static final String KIND = "digest-send";

  private DigestQueue() {}

  /**
   * How digests are queued.
   *
   * @param maxAttempts how many times a digest may fail before it is set aside
   * @param lease how long a worker may hold a digest before it is presumed lost
   * @param initialBackoff the wait after the first failure
   * @param maxBackoff the longest wait between attempts, also the wait while a channel is down
   * @return the policies to build a work queue with
   */
  public static Map<String, QueuePolicy> policies(
      int maxAttempts, Duration lease, Duration initialBackoff, Duration maxBackoff) {
    return Map.of(
        KIND, TransactionalOutbox.orderedPolicy(maxAttempts, lease, initialBackoff, maxBackoff));
  }
}
