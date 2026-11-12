package com.debthunter.controlplane.ai.remediation;

import com.debthunter.controlplane.store.queue.QueuePolicy;
import java.time.Duration;
import java.util.Map;

/** What the remediation queue is: one kind of work, independent per item, de-duplicated. */
public final class RemediationQueue {

  /** The kind of work in the queue. */
  public static final String KIND = "remediation-pr";

  private RemediationQueue() {}

  /**
   * How remediation requests are queued. Items are not ordered against each other (one repository's
   * requests are independent), a request that keeps failing for its own reasons is set aside after
   * {@code maxAttempts}, and a source host that is down only delays the work.
   *
   * @param maxAttempts how many times a request may fail before it is set aside
   * @param lease how long a worker may hold a request before it is presumed lost
   * @param initialBackoff the wait after the first failure
   * @param maxBackoff the longest wait between attempts, also the wait while the host is down
   * @return the policies to build a work queue with
   */
  public static Map<String, QueuePolicy> policies(
      int maxAttempts, Duration lease, Duration initialBackoff, Duration maxBackoff) {
    return Map.of(KIND, new QueuePolicy(false, maxAttempts, lease, initialBackoff, maxBackoff));
  }
}
