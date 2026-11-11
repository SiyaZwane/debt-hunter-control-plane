package com.debthunter.controlplane.analytics.readmodel;

import java.time.Instant;
import java.util.Objects;

/**
 * A finding that is open, and since when.
 *
 * @param repository the repository
 * @param projectSlice the project slice
 * @param fingerprint the finding's fingerprint
 * @param ruleId the rule that raised it
 * @param severity its severity
 * @param category its category
 * @param since when it was first observed, or last reappeared after a resolution
 */
public record OpenFinding(
    String repository,
    String projectSlice,
    String fingerprint,
    String ruleId,
    String severity,
    String category,
    Instant since) {

  /** Validates the components. */
  public OpenFinding {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(projectSlice, "projectSlice");
    Objects.requireNonNull(fingerprint, "fingerprint");
    Objects.requireNonNull(ruleId, "ruleId");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(category, "category");
    Objects.requireNonNull(since, "since");
  }
}
