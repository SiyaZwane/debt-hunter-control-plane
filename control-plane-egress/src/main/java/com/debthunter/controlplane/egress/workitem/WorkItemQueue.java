package com.debthunter.controlplane.egress.workitem;

import com.debthunter.controlplane.egress.outbox.TransactionalOutbox;
import com.debthunter.controlplane.store.queue.QueuePolicy;
import java.time.Duration;
import java.util.Map;

/** What the work-item queue is: one kind of message, ordered per finding. */
public final class WorkItemQueue {

  /** The kind of message in the queue. */
  public static final String KIND = "work-item-sync";

  private WorkItemQueue() {}

  /**
   * How work-item messages are queued.
   *
   * @param maxAttempts how many times a message may fail before it is set aside
   * @param lease how long a worker may hold a message before it is presumed lost
   * @param initialBackoff the wait after the first failure
   * @param maxBackoff the longest wait between attempts, also the wait while a tracker is down
   * @return the policies to build a work queue with
   */
  public static Map<String, QueuePolicy> policies(
      int maxAttempts, Duration lease, Duration initialBackoff, Duration maxBackoff) {
    return Map.of(
        KIND, TransactionalOutbox.orderedPolicy(maxAttempts, lease, initialBackoff, maxBackoff));
  }
}
