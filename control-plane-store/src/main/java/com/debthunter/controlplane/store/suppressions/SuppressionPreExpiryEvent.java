package com.debthunter.controlplane.store.suppressions;

import java.time.Instant;

/**
 * A suppression is about to lapse.
 *
 * @param suppressionId the suppression
 * @param repository the repository
 * @param fingerprint the finding it covers
 * @param expiresAt when it lapses
 * @param raisedAsOf the commit date the warning was raised against
 */
public record SuppressionPreExpiryEvent(
    String suppressionId,
    String repository,
    String fingerprint,
    Instant expiresAt,
    Instant raisedAsOf) {}
