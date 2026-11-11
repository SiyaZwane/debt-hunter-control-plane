package com.debthunter.controlplane.ai.explain;

import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import java.util.Objects;

/**
 * A request to explain one finding.
 *
 * @param repository the repository the finding belongs to
 * @param finding the finding
 */
public record ExplainRequest(String repository, PseudonymisedFinding finding) {

  /** Validates the components. */
  public ExplainRequest {
    if (repository == null || repository.isBlank()) {
      throw new IllegalArgumentException("An explanation request needs a repository");
    }
    Objects.requireNonNull(finding, "finding");
  }
}
