package com.debthunter.controlplane.ai.remediation;

import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import java.util.Objects;

/**
 * A request to propose a remediation for one finding.
 *
 * @param repository the repository the finding belongs to
 * @param runId the run it was reported in
 * @param finding the finding
 * @param baseBranch the branch a pull request would target
 */
public record RemediationRequest(
    String repository, String runId, PseudonymisedFinding finding, String baseBranch) {

  /** Validates the components. */
  public RemediationRequest {
    if (repository == null || repository.isBlank()) {
      throw new IllegalArgumentException("A remediation request needs a repository");
    }
    if (runId == null || runId.isBlank()) {
      throw new IllegalArgumentException("A remediation request needs a run");
    }
    Objects.requireNonNull(finding, "finding");
    if (baseBranch == null || baseBranch.isBlank()) {
      throw new IllegalArgumentException("A remediation request needs a base branch");
    }
  }
}
