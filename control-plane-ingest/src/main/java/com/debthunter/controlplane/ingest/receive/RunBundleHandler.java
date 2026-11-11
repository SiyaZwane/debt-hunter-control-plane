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

  /** What became of a bundle the handler returned normally for. */
  enum Disposition {
    /** It was processed. */
    PROCESSED,
    /**
     * It can never be processed and has been set aside for a person to look at. It is <em>not</em>
     * marked as seen, so a corrected bundle under the same run id is not mistaken for a duplicate.
     */
    SET_ASIDE
  }

  /**
   * Processes one bundle.
   *
   * @param envelope the bundle to process
   * @return what became of it
   * @throws RuntimeException if the bundle could not be processed and should be retried
   */
  Disposition handle(IntakeEnvelope envelope);
}
