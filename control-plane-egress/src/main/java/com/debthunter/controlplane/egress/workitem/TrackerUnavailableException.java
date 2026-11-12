package com.debthunter.controlplane.egress.workitem;

import com.debthunter.controlplane.egress.outbox.DeliveryUnavailableException;

/**
 * The tracker could not take the call now: it is unreachable, timing out, rate-limiting, or not
 * accepting our credentials. None of that is the work item's fault, so the work is held.
 */
public final class TrackerUnavailableException extends DeliveryUnavailableException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what happened; never contains a credential
   * @param cause the underlying failure, or {@code null}
   */
  public TrackerUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
