package com.debthunter.controlplane.serve.query;

import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import java.time.Instant;
import java.util.Objects;

/**
 * A finding as the read APIs show it: where it stands now, and what the latest run that reported it
 * said. There is no field that could hold a person: not a contributor, not even a pseudonym.
 *
 * @param repository the repository
 * @param projectSlice the project slice
 * @param fingerprint the finding's stable fingerprint
 * @param state where it stands now
 * @param findingId its id in the latest run that reported it
 * @param ruleId the rule that raised it
 * @param category what kind of debt
 * @param severity how severe, as of that run
 * @param path the file
 * @param startLine where in it
 * @param message the description
 * @param runId the latest run that reported it, which is where its evidence is
 * @param observedAt the commit date of that run, or {@code null} if not recorded
 */
public record FindingRow(
    String repository,
    String projectSlice,
    String fingerprint,
    FindingState state,
    String findingId,
    String ruleId,
    Category category,
    Severity severity,
    String path,
    int startLine,
    String message,
    String runId,
    Instant observedAt) {

  /** Validates the components. */
  public FindingRow {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(projectSlice, "projectSlice");
    Objects.requireNonNull(fingerprint, "fingerprint");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(category, "category");
  }
}
