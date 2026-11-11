package com.debthunter.controlplane.governance.simulation;

import com.debthunter.domain.Severity;
import java.util.List;
import java.util.Objects;

/**
 * A finding the candidate policy would count against a repository.
 *
 * @param findingId the finding's identifier within its run
 * @param ruleId the analysis rule that raised it
 * @param fingerprint the finding's stable fingerprint
 * @param severity its severity
 * @param path the file it is in
 * @param blockedBy the candidate policy rules that count it
 */
public record BlockingFinding(
    String findingId,
    String ruleId,
    String fingerprint,
    Severity severity,
    String path,
    List<String> blockedBy) {

  /** Validates required fields and defensively copies the rule list. */
  public BlockingFinding {
    Objects.requireNonNull(findingId, "findingId");
    Objects.requireNonNull(ruleId, "ruleId");
    Objects.requireNonNull(fingerprint, "fingerprint");
    Objects.requireNonNull(severity, "severity");
    Objects.requireNonNull(path, "path");
    blockedBy = blockedBy == null ? List.of() : List.copyOf(blockedBy);
  }
}
