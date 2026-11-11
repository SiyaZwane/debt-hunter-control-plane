package com.debthunter.controlplane.api.admin;

/**
 * A repository's entry, as supplied in a request.
 *
 * @param repository the repository identifier; on an update it must match the URL if present
 * @param owner the owning team
 * @param tier its criticality tier
 * @param policyBundleVersion the policy bundle it is assessed against
 * @param rolloutStage how strictly that policy is applied
 * @param route where its work items go
 */
public record RepositoryRequest(
    String repository,
    String owner,
    String tier,
    String policyBundleVersion,
    String rolloutStage,
    RouteRequest route) {}
