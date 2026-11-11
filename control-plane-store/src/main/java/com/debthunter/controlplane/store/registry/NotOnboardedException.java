package com.debthunter.controlplane.store.registry;

/** An operation needed a repository that is not onboarded. */
public class NotOnboardedException extends IllegalStateException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param repository the repository
   */
  public NotOnboardedException(String repository) {
    super("Not onboarded: " + repository);
  }
}
