package com.debthunter.controlplane.store.identity;

/**
 * The directory could not be read or written reliably: a stored identity could not be opened (a
 * wrong key, or a row that was altered or moved), as opposed to a pseudonym that is simply unknown.
 */
public final class PseudonymDirectoryException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what went wrong; never contains an identity
   * @param cause the underlying failure
   */
  public PseudonymDirectoryException(String message, Throwable cause) {
    super(message, cause);
  }
}
