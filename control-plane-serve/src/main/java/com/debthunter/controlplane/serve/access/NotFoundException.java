package com.debthunter.controlplane.serve.access;

/** There is no such thing. */
public final class NotFoundException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what happened; safe to show to the caller
   */
  public NotFoundException(String message) {
    super(message);
  }
}
