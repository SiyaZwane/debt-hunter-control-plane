package com.debthunter.controlplane.store.suppressions;

/** Where suppression events go (notifications, a queue). */
@FunctionalInterface
public interface SuppressionEventPublisher {

  /**
   * Publishes an event.
   *
   * @param event what happened
   * @throws RuntimeException if it could not be published; it will be raised again on the next
   *     sweep
   */
  void publish(SuppressionPreExpiryEvent event);
}
