package com.debthunter.controlplane.store.lifecycle;

import java.util.Objects;

/**
 * A finding's current state.
 *
 * @param repository the repository
 * @param projectSlice the project slice within it
 * @param fingerprint the finding's fingerprint
 * @param state where it stands
 * @param lastObservedCommit the commit at which it was last seen, so seeing it again at the same
 *     commit (a re-run) is not mistaken for seeing it at a later one
 */
public record FindingStateRecord(
    String repository,
    String projectSlice,
    String fingerprint,
    FindingState state,
    String lastObservedCommit) {

  /** Validates the components. */
  public FindingStateRecord {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(projectSlice, "projectSlice");
    Objects.requireNonNull(fingerprint, "fingerprint");
    Objects.requireNonNull(state, "state");
  }
}
