package com.debthunter.controlplane.ai.remediation.host;

/**
 * The source host answered and refused (no such repository, no permission, a request it will not
 * accept). Trying again unchanged will not help.
 */
public final class SourceHostRejectedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what the host refused; never contains a credential
   */
  public SourceHostRejectedException(String message) {
    super(message);
  }
}
