package com.debthunter.controlplane.analytics.readmodel;

import java.time.Instant;
import java.util.Objects;

/**
 * A finding that was resolved, and how long it was open.
 *
 * @param repository the repository
 * @param projectSlice the project slice
 * @param fingerprint the finding's fingerprint
 * @param ruleId the rule that raised it
 * @param severity its severity
 * @param category its category
 * @param resolvedAt when (the commit date)
 * @param durationSeconds how long it had been open, from first observation or last reappearance
 */
public record ResolutionRecord(
    String repository,
    String projectSlice,
    String fingerprint,
    String ruleId,
    String severity,
    String category,
    Instant resolvedAt,
    long durationSeconds) {

  /** Validates the components. */
  public ResolutionRecord {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(projectSlice, "projectSlice");
    Objects.requireNonNull(fingerprint, "fingerprint");
    Objects.requireNonNull(ruleId, "ruleId");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(resolvedAt, "resolvedAt");
  }
}
