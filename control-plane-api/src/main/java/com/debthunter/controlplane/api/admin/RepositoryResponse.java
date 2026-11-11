package com.debthunter.controlplane.api.admin;

import com.debthunter.controlplane.store.registry.OnboardedRepository;

/**
 * A repository's entry, as returned.
 *
 * @param repository the repository identifier
 * @param owner the owning team
 * @param tier its criticality tier
 * @param policyBundleVersion the policy bundle it is assessed against
 * @param rolloutStage how strictly that policy is applied
 * @param route where its work items go
 */
public record RepositoryResponse(
    String repository,
    String owner,
    String tier,
    String policyBundleVersion,
    String rolloutStage,
    RouteRequest route) {

  static RepositoryResponse from(OnboardedRepository entry) {
    return new RepositoryResponse(
        entry.repository(),
        entry.owner(),
        entry.tier(),
        entry.policyBundleVersion(),
        entry.rolloutStage(),
        new RouteRequest(entry.route().tracker(), entry.route().target()));
  }
}
