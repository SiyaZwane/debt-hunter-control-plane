package com.debthunter.controlplane.ingest.pseudonymise;

/** Where re-identification attempts are recorded. */
@FunctionalInterface
public interface ReIdentificationAuditLog {

  /**
   * Records an attempt.
   *
   * @param entry what happened
   * @throws RuntimeException if it could not be recorded; the re-identification then does not
   *     happen, because an unrecorded disclosure is worse than a refused one
   */
  void record(ReIdentificationAuditEntry entry);
}
