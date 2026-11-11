package com.debthunter.controlplane.governance.simulation;

import java.util.List;

/**
 * The candidate policy's impact across the estate, overall and sliced by criticality tier and by
 * rollout stage, because "how many fail" matters differently for a tier-1 service at the final
 * stage than for a tier-3 one still in advisory rollout.
 *
 * @param overall the whole estate
 * @param byTier one entry per criticality tier, sorted by tier
 * @param byStage one entry per rollout stage, sorted by stage
 * @param byTierAndStage one entry per tier and stage that has a repository, sorted
 */
public record EstateSummary(
    GroupSummary overall,
    List<GroupSummary> byTier,
    List<GroupSummary> byStage,
    List<GroupSummary> byTierAndStage) {

  /** Defensively copies the lists. */
  public EstateSummary {
    byTier = List.copyOf(byTier);
    byStage = List.copyOf(byStage);
    byTierAndStage = List.copyOf(byTierAndStage);
  }
}
