package com.debthunter.controlplane.governance.publication;

/** A signature could not be produced. Never accompanied by a partial signature. */
public final class SigningException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what went wrong
   * @param cause the underlying failure
   */
  public SigningException(String message, Throwable cause) {
    super(message, cause);
  }
}
