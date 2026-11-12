package com.debthunter.controlplane.egress.outbox;

/**
 * What one pass over the outbox did.
 *
 * @param delivered messages delivered
 * @param held messages put back untouched because the destination was unavailable
 * @param retried messages that failed and will be tried again
 * @param deadLettered messages set aside, each with an alert raised
 */
public record DrainResult(int delivered, int held, int retried, int deadLettered) {

  /** Everything the pass looked at. */
  public int total() {
    return delivered + held + retried + deadLettered;
  }
}
