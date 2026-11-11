package com.debthunter.controlplane.governance.publication;

/** The registry could not complete an operation, or refused it. */
public class RegistryException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what went wrong
   */
  public RegistryException(String message) {
    super(message);
  }

  /**
   * Creates the exception.
   *
   * @param message what went wrong
   * @param cause the underlying failure
   */
  public RegistryException(String message, Throwable cause) {
    super(message, cause);
  }
}
