package com.debthunter.controlplane.governance.publication;

/** A bundle was fetched but cannot be trusted: its content, digest or signature does not check. */
public final class UntrustedBundleException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the exception.
   *
   * @param message why the bundle is not trusted
   */
  public UntrustedBundleException(String message) {
    super(message);
  }
}
