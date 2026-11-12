package com.debthunter.controlplane.serve.access;

/** The request was not valid. */
public final class InvalidRequestException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what happened; safe to show to the caller
   */
  public InvalidRequestException(String message) {
    super(message);
  }
}
