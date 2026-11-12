package com.debthunter.controlplane.egress.digest;

/**
 * The channel answered and refused (the webhook was revoked or the message was not accepted).
 * Trying again unchanged will not help, so attempts count and the digest is eventually set aside
 * with an alert.
 */
public final class ChannelRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what was refused; never contains the webhook address
   */
  public ChannelRejectedException(String message) {
    super(message);
  }
}
