package com.debthunter.controlplane.store.evidence;

import java.time.Instant;
import java.util.Objects;

/**
 * One request for a run's evidence.
 *
 * @param at when it was made
 * @param caller who made it (the token's subject)
 * @param runId the run asked for
 * @param repository the run's repository, or {@code null} if there was no such run
 * @param purpose the reason the caller gave
 * @param outcome what was decided
 */
public record EvidenceAccess(
    Instant at, String caller, String runId, String repository, String purpose, Outcome outcome) {

  /** What was decided about a request. */
  public enum Outcome {
    /** The evidence was returned. */
    GRANTED,
    /** The caller may not see that repository's evidence. */
    DENIED,
    /** There is no such run. */
    NOT_FOUND
  }

  /** Validates the components. */
  public EvidenceAccess {
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(purpose, "purpose");
    Objects.requireNonNull(outcome, "outcome");
  }
}
