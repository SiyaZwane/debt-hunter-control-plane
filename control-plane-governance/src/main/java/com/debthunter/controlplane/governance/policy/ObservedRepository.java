package com.debthunter.controlplane.governance.policy;

import com.debthunter.domain.Finding;
import com.debthunter.domain.SuppressionEntry;
import java.util.List;
import java.util.Objects;

/**
 * What one repository's most recent complete run looked like: its findings and the suppressions
 * that were in force. The raw material for asking "how would a threshold fare against what this
 * estate really looks like?".
 *
 * @param repository the repository identifier
 * @param findings the run's findings, with {@code isNew} as the data plane recorded it
 * @param suppressions the suppressions in force for the run's findings
 */
public record ObservedRepository(
    String repository, List<Finding> findings, List<SuppressionEntry> suppressions) {

  /** Validates required fields and defensively copies the lists. */
  public ObservedRepository {
    Objects.requireNonNull(repository, "repository");
    findings = findings == null ? List.of() : List.copyOf(findings);
    suppressions = suppressions == null ? List.of() : List.copyOf(suppressions);
  }
}
