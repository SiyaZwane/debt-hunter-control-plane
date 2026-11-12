package com.debthunter.controlplane.egress.digest;

import com.debthunter.controlplane.egress.outbox.DeliveryUnavailableException;

/**
 * The channel could not take the message now (unreachable, timing out, a server error or a request
 * to slow down), or there is nowhere to send it yet. The digest is held, not lost.
 */
public final class ChannelUnavailableException extends DeliveryUnavailableException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what happened; never contains the webhook address
   * @param cause the underlying failure, or {@code null}
   */
  public ChannelUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
