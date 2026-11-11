package com.debthunter.controlplane.store.registry;

/** A repository was onboarded that is already onboarded. */
public class AlreadyOnboardedException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param repository the repository
   */
  public AlreadyOnboardedException(String repository) {
    super("Already onboarded: " + repository);
  }
}
