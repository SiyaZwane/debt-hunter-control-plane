package com.debthunter.controlplane.ingest.pseudonymise;

/** A re-identification request was refused. The attempt has been audited. */
public class ReIdentificationDeniedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message why it was refused
   */
  public ReIdentificationDeniedException(String message) {
    super(message);
  }
}
