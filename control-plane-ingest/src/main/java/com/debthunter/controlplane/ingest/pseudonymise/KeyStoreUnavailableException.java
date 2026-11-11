package com.debthunter.controlplane.ingest.pseudonymise;

/**
 * The pseudonym key could not be obtained. Transient: ingest must hold the bundle and retry, and
 * must never fall back to processing it without pseudonymisation.
 */
public class KeyStoreUnavailableException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what failed; never the key
   * @param cause the underlying failure
   */
  public KeyStoreUnavailableException(String message, Throwable cause) {
    super(message, cause);
  }

  /**
   * Creates the exception.
   *
   * @param message what failed; never the key
   */
  public KeyStoreUnavailableException(String message) {
    super(message);
  }
}
