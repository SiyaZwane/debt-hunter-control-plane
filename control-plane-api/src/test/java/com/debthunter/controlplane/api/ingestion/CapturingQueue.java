package com.debthunter.controlplane.api.ingestion;

import com.debthunter.controlplane.ingest.intake.IntakeEnvelope;
import com.debthunter.controlplane.ingest.intake.RunIntakeQueuePublisher;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;

/** A queue double that records what it was given and can be told to fail. */
public final class CapturingQueue implements RunIntakeQueuePublisher {

  private final List<IntakeEnvelope> published = new CopyOnWriteArrayList<>();
  private volatile RuntimeException failure;

  /**
   * Makes every later publish fail with the given exception.
   *
   * @param failure what to fail with, or {@code null} to succeed again
   */
  public void failWith(RuntimeException failure) {
    this.failure = failure;
  }

  /**
   * What has been published.
   *
   * @return the envelopes accepted so far, in order
   */
  public List<IntakeEnvelope> published() {
    return published;
  }

  @Override
  public CompletionStage<Void> publish(IntakeEnvelope envelope) {
    RuntimeException toThrow = failure;
    if (toThrow != null) {
      return CompletableFuture.failedFuture(toThrow);
    }
    published.add(envelope);
    return CompletableFuture.completedFuture(null);
  }
}
