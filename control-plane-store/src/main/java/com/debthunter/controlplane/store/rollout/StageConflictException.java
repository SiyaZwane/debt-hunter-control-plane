package com.debthunter.controlplane.store.rollout;

/** A stage change lost a race: the repository was no longer where the change assumed. */
public final class StageConflictException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what was expected and what was found
   */
  public StageConflictException(String message) {
    super(message);
  }
}
