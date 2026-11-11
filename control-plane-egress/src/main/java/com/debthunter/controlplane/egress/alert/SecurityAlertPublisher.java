package com.debthunter.controlplane.egress.alert;

/**
 * Raises a {@link SecurityAlert} to whoever needs to see it.
 *
 * <p>Raising an alert must never disturb the request that triggered it. Implementations therefore
 * must not throw and must not block the caller for any meaningful time; a failure to deliver is the
 * implementation's to log, not the caller's to handle.
 */
public interface SecurityAlertPublisher {

  /**
   * Raises an alert. Never throws.
   *
   * @param alert the event to raise
   */
  void publish(SecurityAlert alert);
}
