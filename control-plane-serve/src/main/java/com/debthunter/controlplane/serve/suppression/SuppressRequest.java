package com.debthunter.controlplane.serve.suppression;

import java.time.Instant;

/**
 * A squad's request to set a finding aside until a date.
 *
 * @param repository the repository the finding is in
 * @param fingerprint the finding
 * @param reason why; required
 * @param expiresAt when the suppression lapses; within the policy's maximum from now
 */
public record SuppressRequest(
    String repository, String fingerprint, String reason, Instant expiresAt) {}
