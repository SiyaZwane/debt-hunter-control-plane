package com.debthunter.controlplane.api.ingestion;

import java.util.UUID;

/**
 * The entire body of a {@code 202 Accepted}: an id for tracing the bundle, and nothing else. No
 * verdict and no advice — the pipeline gets an acknowledgement, never a decision it could wait on.
 *
 * @param correlationId the id assigned to the accepted bundle
 */
public record AcceptedResponse(UUID correlationId) {}
