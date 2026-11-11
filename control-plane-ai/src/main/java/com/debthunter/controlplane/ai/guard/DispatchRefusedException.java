package com.debthunter.controlplane.ai.guard;

/** The guard would not send a prompt, because it could not be recorded. */
public final class DispatchRefusedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message why
   * @param cause the underlying failure
   */
  public DispatchRefusedException(String message, Throwable cause) {
    super(message, cause);
  }
}
