package com.debthunter.controlplane.store.jdbc;

/** The store could not complete an operation. Callers treat it as transient: hold and retry. */
public class StoreException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what failed; never row contents
   * @param cause the underlying failure
   */
  public StoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
