package com.debthunter.controlplane.store.quarantine;

import java.time.Instant;
import java.util.Objects;

/**
 * A bundle that was set aside because it can never be processed.
 *
 * <p>There is deliberately no field for the bundle's content: it still carries raw contributor
 * identity, which must not be written to the store. The original stays on the intake topic.
 *
 * @param correlationId the id the pipeline was given when the bundle was accepted
 * @param runId the bundle's own run id
 * @param repository the repository it declared
 * @param workloadIdentity who published it
 * @param schemaVersion the schema version it declared
 * @param reason why it could not be processed; names the offending field, never its value
 * @param receivedAt when the edge accepted it
 * @param quarantinedAt when it was set aside
 */
public record QuarantinedBundle(
    String correlationId,
    String runId,
    String repository,
    String workloadIdentity,
    String schemaVersion,
    String reason,
    Instant receivedAt,
    Instant quarantinedAt) {

  /** Validates the components. */
  public QuarantinedBundle {
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(workloadIdentity, "workloadIdentity");
    Objects.requireNonNull(schemaVersion, "schemaVersion");
    Objects.requireNonNull(reason, "reason");
    Objects.requireNonNull(receivedAt, "receivedAt");
    Objects.requireNonNull(quarantinedAt, "quarantinedAt");
  }
}
