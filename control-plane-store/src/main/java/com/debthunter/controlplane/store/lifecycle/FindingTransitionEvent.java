package com.debthunter.controlplane.store.lifecycle;

import java.time.Instant;
import java.util.Objects;

/**
 * One immutable fact in the append-only transition log: a finding moved from one state to another
 * because of a run.
 *
 * @param eventId deterministic id, unique per (run, fingerprint, step), which makes appending a
 *     retried event a no-op rather than a duplicate
 * @param repository the repository
 * @param projectSlice the project slice within it
 * @param fingerprint the finding's fingerprint
 * @param fromState the state it left, or {@code null} for a first observation
 * @param toState the state it entered
 * @param runId the run that caused the move
 * @param timestamp when, as the commit date (never the control plane's wall clock)
 * @param cause why
 */
public record FindingTransitionEvent(
    String eventId,
    String repository,
    String projectSlice,
    String fingerprint,
    FindingState fromState,
    FindingState toState,
    String runId,
    Instant timestamp,
    TransitionCause cause) {

  /** Validates the components, including that the move is one the state machine allows. */
  public FindingTransitionEvent {
    Objects.requireNonNull(eventId, "eventId");
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(projectSlice, "projectSlice");
    Objects.requireNonNull(fingerprint, "fingerprint");
    Objects.requireNonNull(toState, "toState");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(timestamp, "timestamp");
    Objects.requireNonNull(cause, "cause");
    boolean legal =
        fromState == null ? FindingState.canStartIn(toState) : fromState.canMoveTo(toState);
    if (!legal) {
      throw new IllegalArgumentException("Illegal transition " + fromState + " -> " + toState);
    }
  }
}
