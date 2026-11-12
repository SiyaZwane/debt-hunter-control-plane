package com.debthunter.controlplane.egress.outbox;

/**
 * Delivery cannot happen now for a reason that is not the message's fault: the destination is down
 * or rate-limiting, or something it needs (such as a routing entry) is missing and may yet appear.
 * The message is held, not counted against, and tried again later; it is never set aside because of
 * this.
 */
public class DeliveryUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what is unavailable; never contains a credential or a payload
   * @param cause the underlying failure, or {@code null}
   */
  public DeliveryUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
