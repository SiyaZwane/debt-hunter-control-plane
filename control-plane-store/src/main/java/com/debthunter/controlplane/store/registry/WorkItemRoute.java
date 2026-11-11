package com.debthunter.controlplane.store.registry;

import java.util.Objects;

/**
 * Where a repository's work items go.
 *
 * @param tracker the tracking system, such as {@code jira} or {@code azure-boards}
 * @param target the project, board or queue within it
 */
public record WorkItemRoute(String tracker, String target) {

  /** Validates the components. */
  public WorkItemRoute {
    Objects.requireNonNull(tracker, "tracker");
    Objects.requireNonNull(target, "target");
    if (tracker.isBlank() || target.isBlank()) {
      throw new IllegalArgumentException("A work-item route needs a tracker and a target");
    }
  }
}
