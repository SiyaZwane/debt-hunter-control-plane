package com.debthunter.controlplane.ingest.intake;

import java.time.Instant;
import java.util.UUID;

/** Builds envelopes for tests. */
public final class IntakeEnvelopes {

  private IntakeEnvelopes() {}

  /**
   * An envelope for a run in the default repository.
   *
   * @param runId the run id
   * @return the envelope
   */
  public static IntakeEnvelope forRun(String runId) {
    return forRun(runId, "acme/widgets");
  }

  /**
   * An envelope for a run in a given repository.
   *
   * @param runId the run id
   * @param repository the declared repository
   * @return the envelope
   */
  public static IntakeEnvelope forRun(String runId, String repository) {
    return new IntakeEnvelope(
        UUID.randomUUID(),
        "svc-a",
        repository,
        "1.0",
        Instant.parse("2026-01-01T00:00:00Z"),
        runId,
        "{\"schemaVersion\":\"1.0\",\"run\":{\"id\":\"" + runId + "\"}}");
  }
}
