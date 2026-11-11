package com.debthunter.controlplane.api.ingestion;

import com.debthunter.controlplane.store.registry.RepositoryScopeLookup;
import java.util.Objects;

/**
 * Checks the caller's authenticated workload identity against the repository a bundle declares, so
 * one pipeline can never write another repository's data. Authentication — that the token is
 * genuine — happens earlier, in the security filter chain; this answers the separate question of
 * what an authenticated caller is allowed to do.
 *
 * <p>Fails closed: a missing identity, a missing repository, and a repository outside the
 * identity's scope are all refusals.
 */
public final class WorkloadIdentityValidator {

  private final RepositoryScopeLookup scopes;

  /**
   * Creates a validator.
   *
   * @param scopes says which repositories each identity may write for
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "the lookup is a shared service, held by reference intentionally")
  public WorkloadIdentityValidator(RepositoryScopeLookup scopes) {
    this.scopes = Objects.requireNonNull(scopes, "scopes");
  }

  /**
   * Decides whether {@code workloadIdentity} may publish a bundle declaring {@code repository}.
   *
   * @param workloadIdentity the authenticated caller, or {@code null}
   * @param repository the repository the bundle declares, or {@code null}
   * @return whether to allow it, and if not, why
   */
  public ScopeDecision validate(String workloadIdentity, String repository) {
    if (workloadIdentity == null || workloadIdentity.isBlank()) {
      return ScopeDecision.deny("no workload identity");
    }
    if (repository == null || repository.isBlank()) {
      return ScopeDecision.deny("no declared repository");
    }
    if (!scopes.mayWrite(workloadIdentity, repository)) {
      return ScopeDecision.deny("repository is outside the identity's scope");
    }
    return ScopeDecision.allow();
  }
}
