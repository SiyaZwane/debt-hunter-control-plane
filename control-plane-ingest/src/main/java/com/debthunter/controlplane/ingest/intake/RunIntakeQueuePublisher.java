package com.debthunter.controlplane.ingest.intake;

import java.util.concurrent.CompletionStage;

/**
 * Hands a validated bundle to the intake queue.
 *
 * <p>This is the seam that keeps the ingestion edge fast, so its contract is strict. Publishing
 * must complete within a bounded time, never indefinitely; and when the queue cannot take the
 * bundle the returned stage must complete exceptionally with {@link QueueUnavailableException} —
 * never with a broker-specific type, and never by returning normally. Every implementation, fake or
 * real, honours the same contract, so a test against a fake says something true about the real one.
 */
public interface RunIntakeQueuePublisher {

  /**
   * Publishes a bundle. May block the calling thread briefly while the queue's metadata is
   * resolved, so callers on a non-blocking thread must offload it.
   *
   * @param envelope the bundle to enqueue
   * @return a stage that completes once the queue has durably accepted the bundle, or completes
   *     exceptionally with {@link QueueUnavailableException}
   */
  CompletionStage<Void> publish(IntakeEnvelope envelope);
}
