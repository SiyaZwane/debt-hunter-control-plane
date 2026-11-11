package com.debthunter.controlplane.store.lifecycle;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

/** The current state of every finding, per repository and project slice. */
public interface FindingStateRepository {

  /**
   * Reads every finding's current state in a slice.
   *
   * @param repository the repository
   * @param projectSlice the project slice
   * @return fingerprint to record
   */
  Map<String, FindingStateRecord> statesFor(String repository, String projectSlice);

  /**
   * Reads one finding's current state.
   *
   * @param repository the repository
   * @param projectSlice the project slice
   * @param fingerprint the fingerprint
   * @return its record, or empty if it has never been seen
   */
  default Optional<FindingStateRecord> find(
      String repository, String projectSlice, String fingerprint) {
    return Optional.ofNullable(statesFor(repository, projectSlice).get(fingerprint));
  }

  /**
   * The fingerprints that count against a gate: findings that are new, open or regressed, but not
   * resolved and not suppressed.
   *
   * @param repository the repository
   * @param projectSlice the project slice
   * @return the fingerprints that block gating
   */
  default java.util.Set<String> blockingFingerprints(String repository, String projectSlice) {
    return statesFor(repository, projectSlice).values().stream()
        .filter(r -> r.state().blocksGating())
        .map(FindingStateRecord::fingerprint)
        .collect(java.util.stream.Collectors.toSet());
  }

  /**
   * Writes states, replacing any existing record for the same fingerprint. Idempotent.
   *
   * @param records the new states
   */
  void putAll(Collection<FindingStateRecord> records);
}
