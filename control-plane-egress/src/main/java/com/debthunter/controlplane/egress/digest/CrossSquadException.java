package com.debthunter.controlplane.egress.digest;

/** A digest was asked for about a repository that the squad does not own. */
public final class CrossSquadException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what was asked
   */
  public CrossSquadException(String message) {
    super(message);
  }
}
