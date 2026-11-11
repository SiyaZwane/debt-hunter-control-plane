package com.debthunter.controlplane.ingest.receive;

import com.debthunter.controlplane.ingest.intake.IntakeEnvelope;

/**
 * The next stage of ingest, which a received bundle is dispatched to.
 *
 * <p>Failure is signalled by throwing. A thrown exception means "not processed": the bundle is not
 * marked as seen and will be redelivered, so a transient fault never silently loses a bundle.
 */
@FunctionalInterface
public interface RunBundleHandler {

  /**
   * Processes one bundle.
   *
   * @param envelope the bundle to process
   * @throws RuntimeException if the bundle could not be processed and should be retried
   */
  void handle(IntakeEnvelope envelope);
}
