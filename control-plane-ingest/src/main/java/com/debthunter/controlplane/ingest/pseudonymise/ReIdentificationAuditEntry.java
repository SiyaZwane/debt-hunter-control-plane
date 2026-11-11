package com.debthunter.controlplane.ingest.pseudonymise;

import com.debthunter.controlplane.store.identity.Pseudonym;
import java.time.Instant;
import java.util.Objects;

/**
 * One line of the re-identification audit trail. It records who asked about which pseudonym and
 * why, and never the identity that was revealed.
 *
 * @param caller who asked
 * @param pseudonym whom they asked about
 * @param justification the reason they gave (possibly blank, if that is why they were refused)
 * @param at when
 * @param outcome whether it was granted
 */
public record ReIdentificationAuditEntry(
    String caller, Pseudonym pseudonym, String justification, Instant at, Outcome outcome) {

  /** What became of the request. */
  public enum Outcome {
    /** The caller was authorised and the identity was looked up. */
    GRANTED,
    /** The caller lacked the authority or gave no justification. */
    DENIED
  }

  /** Validates the components. */
  public ReIdentificationAuditEntry {
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(pseudonym, "pseudonym");
    Objects.requireNonNull(at, "at");
    Objects.requireNonNull(outcome, "outcome");
    justification = justification == null ? "" : justification;
  }
}
