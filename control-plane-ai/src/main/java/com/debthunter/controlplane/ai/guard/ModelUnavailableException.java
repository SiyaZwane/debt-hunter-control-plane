package com.debthunter.controlplane.ai.guard;

/** The model could not be reached or did not answer. */
public final class ModelUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what went wrong
   * @param cause the underlying failure
   */
  public ModelUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
