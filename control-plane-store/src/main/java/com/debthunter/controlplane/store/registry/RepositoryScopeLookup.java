package com.debthunter.controlplane.store.registry;

/**
 * Answers one question for the ingestion edge: may this workload identity write run bundles for
 * this repository?
 *
 * <p>Deliberately a single-method, role-specific port rather than the whole repository registry, so
 * callers depend only on the slice they use. Implementations must fail closed: when scope cannot be
 * established the answer is {@code false} (or an exception that the caller treats as a rejection),
 * never an implicit grant.
 */
public interface RepositoryScopeLookup {

  /**
   * Whether {@code workloadIdentity} is permitted to publish run bundles declaring {@code
   * repository}.
   *
   * @param workloadIdentity the authenticated caller, or {@code null}
   * @param repository the repository the bundle declares, or {@code null}
   * @return {@code true} only if the identity is explicitly scoped to that repository
   */
  boolean mayWrite(String workloadIdentity, String repository);
}
