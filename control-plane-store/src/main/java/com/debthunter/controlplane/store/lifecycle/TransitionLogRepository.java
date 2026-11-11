package com.debthunter.controlplane.store.lifecycle;

import java.util.List;

/**
 * The append-only record of every finding state change.
 *
 * <p>By design there is no update and no delete: history is only ever added to. Appending an event
 * whose id is already present is a no-op, so a retried append after a partial failure never
 * duplicates it.
 */
public interface TransitionLogRepository {

  /**
   * Appends events, skipping any whose id is already in the log.
   *
   * @param events the events, in order
   * @throws RuntimeException if the log could not be written; the caller must not go on to change
   *     any state that depends on these events
   */
  void append(List<FindingTransitionEvent> events);

  /**
   * Reads one finding's history in the order it was appended.
   *
   * @param repository the repository
   * @param projectSlice the project slice
   * @param fingerprint the fingerprint
   * @return its events, oldest first
   */
  List<FindingTransitionEvent> historyOf(
      String repository, String projectSlice, String fingerprint);

  /**
   * Reads the events a run caused.
   *
   * @param runId the run
   * @return its events in the order they were appended
   */
  List<FindingTransitionEvent> eventsOfRun(String runId);
}
