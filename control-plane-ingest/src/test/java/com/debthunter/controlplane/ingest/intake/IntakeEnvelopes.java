package com.debthunter.controlplane.ingest.intake;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

  /**
   * An envelope carrying a given bundle, taking the run id, repository and schema version from the
   * bundle itself where it has them.
   *
   * @param payloadJson the bundle exactly as a pipeline published it
   * @return the envelope
   */
  public static IntakeEnvelope forBundle(String payloadJson) {
    JsonNode root;
    try {
      root = new ObjectMapper().readTree(payloadJson);
    } catch (JsonProcessingException e) {
      root = null;
    }
    return new IntakeEnvelope(
        UUID.randomUUID(),
        "svc-a",
        root == null ? "acme/widgets" : root.at("/run/repository").asText("acme/widgets"),
        root == null ? "1.0" : root.at("/schemaVersion").asText("1.0"),
        Instant.parse("2026-01-01T00:00:00Z"),
        root == null ? "run-unknown" : root.at("/run/id").asText("run-unknown"),
        payloadJson);
  }
}
