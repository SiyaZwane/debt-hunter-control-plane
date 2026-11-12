package com.debthunter.controlplane.egress.alert;

/**
 * Raises an {@link OperationsAlert} to whoever needs to see it.
 *
 * <p>Raising an alert must never disturb the work that triggered it: implementations must not throw
 * and must not block for any meaningful time.
 */
public interface OperationsAlertPublisher {

  /**
   * Raises an alert. Never throws.
   *
   * @param alert the event to raise
   */
  void publish(OperationsAlert alert);
}
