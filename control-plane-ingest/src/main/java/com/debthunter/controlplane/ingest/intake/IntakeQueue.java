package com.debthunter.controlplane.ingest.intake;

import com.debthunter.controlplane.store.queue.QueuePolicy;
import java.time.Duration;
import java.util.Map;

/** What the intake queue is: one kind of work, ordered per repository. */
public final class IntakeQueue {

  /** The kind of work in the queue. */
  public static final String KIND = "run-intake";

  private IntakeQueue() {}

  /**
   * How intake is queued: oldest first within a repository, and a bundle that keeps failing is set
   * aside after {@code maxAttempts} rather than blocking its repository for ever.
   *
   * @param maxAttempts how many times a bundle may be tried
   * @param lease how long a worker may hold a bundle before it is presumed lost
   * @param initialBackoff the wait after the first failure
   * @param maxBackoff the longest wait between attempts
   * @return the policies to build a work queue with
   */
  public static Map<String, QueuePolicy> policies(
      int maxAttempts, Duration lease, Duration initialBackoff, Duration maxBackoff) {
    return Map.of(KIND, new QueuePolicy(true, maxAttempts, lease, initialBackoff, maxBackoff));
  }

  /** What a payload is bound to when it is sealed, so it cannot be moved to another repository. */
  static String context(String repository) {
    return KIND + ":" + repository;
  }
}
