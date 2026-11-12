package com.debthunter.controlplane.serve.access;

/** The data could not be read now. */
public final class ServiceUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what happened; safe to show to the caller
   */
  public ServiceUnavailableException(String message) {
    super(message);
  }
}
