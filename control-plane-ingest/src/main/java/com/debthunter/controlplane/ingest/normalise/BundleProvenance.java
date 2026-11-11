package com.debthunter.controlplane.ingest.normalise;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Where a normalised bundle came from, carried alongside it so nothing downstream has to go back to
 * the queue envelope.
 *
 * @param correlationId the id the pipeline was given when the bundle was accepted
 * @param workloadIdentity the authenticated caller that published it
 * @param receivedAt when the edge accepted it
 * @param sourceSchemaVersion the schema version the bundle arrived in
 */
public record BundleProvenance(
    UUID correlationId, String workloadIdentity, Instant receivedAt, String sourceSchemaVersion) {

  /** Validates the components. */
  public BundleProvenance {
    Objects.requireNonNull(correlationId, "correlationId");
    Objects.requireNonNull(workloadIdentity, "workloadIdentity");
    Objects.requireNonNull(receivedAt, "receivedAt");
    Objects.requireNonNull(sourceSchemaVersion, "sourceSchemaVersion");
  }
}
