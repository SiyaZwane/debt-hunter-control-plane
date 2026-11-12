package com.debthunter.controlplane.ai.remediation;

/**
 * Whether a repository has agreed to have remediation pull requests opened against it. Off unless
 * someone turned it on.
 *
 * <p>Failure contract: it may throw if the answer cannot be had, and the caller then treats the
 * repository as not enabled. An unknown answer is never read as consent.
 */
public interface RemediationEnabledLookup {

  /**
   * Whether the repository has opted in.
   *
   * @param repository the repository
   * @return true only if it explicitly has
   */
  boolean enabled(String repository);
}
