package com.debthunter.controlplane.egress.outbox;

/**
 * The message can never be delivered (it cannot be read, or it refers to something that does not
 * exist). Trying again cannot help, so it is set aside at once and an alert is raised.
 */
public final class UndeliverableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message why; never contains a payload or a credential
   */
  public UndeliverableException(String message) {
    super(message);
  }
}
