package com.debthunter.controlplane.store.registry;

import java.util.Objects;

/**
 * A repository the control plane has been told about.
 *
 * @param repository the repository identifier
 * @param owner the owning team
 * @param tier its criticality tier
 * @param policyBundleVersion the policy bundle it is assessed against
 * @param rolloutStage how strictly that policy is applied to it
 * @param route where its work items are routed
 */
public record OnboardedRepository(
    String repository,
    String owner,
    String tier,
    String policyBundleVersion,
    String rolloutStage,
    WorkItemRoute route) {

  /** Validates the components. */
  public OnboardedRepository {
    for (String value : new String[] {repository, owner, tier, policyBundleVersion, rolloutStage}) {
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException("An onboarded repository needs every field");
      }
    }
    Objects.requireNonNull(route, "route");
  }
}
