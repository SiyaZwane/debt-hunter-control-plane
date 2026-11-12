package com.debthunter.controlplane.ai.remediation.host;

/**
 * The source host could not be reached or asked to wait (it is down, timing out, or rate-limiting
 * us). The work is not at fault and should be tried again later.
 */
public final class SourceHostUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what went wrong; never contains a credential
   * @param cause the underlying failure
   */
  public SourceHostUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
