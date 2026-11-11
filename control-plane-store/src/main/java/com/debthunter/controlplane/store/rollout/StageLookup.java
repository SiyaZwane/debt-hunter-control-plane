package com.debthunter.controlplane.store.rollout;

import java.util.Map;

/**
 * Read-only view of where repositories are in the rollout, for reports that need to show a stage
 * and have no business changing one.
 */
public interface StageLookup {

  /**
   * The current stage of every repository that is in the rollout.
   *
   * @return stage by repository identifier; a repository not in the rollout is absent
   */
  Map<String, RolloutStage> currentStages();
}
