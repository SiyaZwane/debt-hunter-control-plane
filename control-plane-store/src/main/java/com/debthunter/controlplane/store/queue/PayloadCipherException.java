package com.debthunter.controlplane.store.queue;

/** A payload could not be sealed or opened: wrong key, damaged, or moved to another context. */
public final class PayloadCipherException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what went wrong
   * @param cause the underlying failure
   */
  public PayloadCipherException(String message, Throwable cause) {
    super(message, cause);
  }
}
