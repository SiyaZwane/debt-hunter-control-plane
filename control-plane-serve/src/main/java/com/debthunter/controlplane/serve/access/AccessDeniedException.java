package com.debthunter.controlplane.serve.access;

/** The caller may not do that. */
public final class AccessDeniedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what happened; safe to show to the caller
   */
  public AccessDeniedException(String message) {
    super(message);
  }
}
