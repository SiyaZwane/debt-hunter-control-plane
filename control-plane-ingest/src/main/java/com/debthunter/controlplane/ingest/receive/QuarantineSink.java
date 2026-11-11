package com.debthunter.controlplane.ingest.receive;

import com.debthunter.controlplane.ingest.intake.IntakeEnvelope;

/**
 * Where a bundle goes when it can never be processed, so that it is set aside for a person to look
 * at instead of being retried forever or silently lost.
 */
@FunctionalInterface
public interface QuarantineSink {

  /**
   * Sets a bundle aside.
   *
   * @param envelope the bundle that could not be processed
   * @param reason why, naming the offending field but not echoing its value
   * @throws RuntimeException if it could not be set aside; the bundle will then be retried, which
   *     is right, because dropping it would lose it
   */
  void quarantine(IntakeEnvelope envelope, String reason);
}
