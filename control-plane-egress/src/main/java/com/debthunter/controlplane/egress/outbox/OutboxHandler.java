package com.debthunter.controlplane.egress.outbox;

/** Delivers one message to its destination. */
@FunctionalInterface
public interface OutboxHandler {

  /**
   * Delivers a message. Returning normally means it was delivered (or had already been: delivery
   * must be idempotent, because a message can be handed out more than once).
   *
   * @param message the message
   * @throws DeliveryUnavailableException if the destination cannot take it now, through no fault of
   *     the message
   * @throws UndeliverableException if it can never be delivered
   * @throws RuntimeException for any other failure, which counts as a failed attempt
   */
  void deliver(OutboxMessage message);
}
