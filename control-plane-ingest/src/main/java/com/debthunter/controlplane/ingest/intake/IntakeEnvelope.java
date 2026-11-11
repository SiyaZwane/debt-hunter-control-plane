package com.debthunter.controlplane.ingest.intake;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A validated run bundle on its way through the intake queue, wrapped with what the edge learned
 * about it: who sent it, which repository it declared, and when it arrived.
 *
 * @param correlationId the id returned to the pipeline, for tracing a bundle end to end
 * @param workloadIdentity the authenticated caller that published it
 * @param repository the repository the bundle declares (verified to be within the caller's scope)
 * @param schemaVersion the bundle's declared schema version
 * @param receivedAt when the edge accepted it
 * @param runId the bundle's own run id, the basis for de-duplication
 * @param payloadJson the bundle exactly as received
 */
public record IntakeEnvelope(
    UUID correlationId,
    String workloadIdentity,
    String repository,
    String schemaVersion,
    Instant receivedAt,
    String runId,
    String payloadJson) {

  /** Validates that every component is present. */
  public IntakeEnvelope {
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(workloadIdentity, "workloadIdentity");
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(schemaVersion, "schemaVersion");
    Objects.requireNonNull(receivedAt, "receivedAt");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(payloadJson, "payloadJson");
  }
}
