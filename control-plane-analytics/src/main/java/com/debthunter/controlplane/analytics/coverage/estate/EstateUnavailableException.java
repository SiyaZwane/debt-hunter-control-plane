package com.debthunter.controlplane.analytics.coverage.estate;

/**
 * The source-control estate could not be listed. The message never contains a credential or the
 * remote system's response body.
 */
public class EstateUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what went wrong, safe to log
   */
  public EstateUnavailableException(String message) {
    super(message);
  }

  /**
   * Creates the exception.
   *
   * @param message what went wrong, safe to log
   * @param cause the underlying failure
   */
  public EstateUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }
}
