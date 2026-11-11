package com.debthunter.controlplane.ingest.normalise;

/**
 * A bundle that can never be normalised, however often it is retried: it is not valid JSON, is
 * missing something every version requires, or declares a schema version with no bridge.
 *
 * <p>This is the <em>permanent</em> failure kind. Callers must not retry it; transient faults are
 * ordinary runtime exceptions and are a different thing.
 */
public class UnnormalisableBundleException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message what is wrong, naming the offending field but never echoing its value
   */
  public UnnormalisableBundleException(String message) {
    super(message);
  }

  /**
   * Creates the exception with a cause.
   *
   * @param message what is wrong
   * @param cause the underlying failure
   */
  public UnnormalisableBundleException(String message, Throwable cause) {
    super(message, cause);
  }
}
