package com.debthunter.controlplane.egress.digest;

import com.debthunter.domain.Severity;
import java.util.Map;

/** How many findings are open now in a repository. */
@FunctionalInterface
public interface OpenFindingCounts {

  /**
   * Counts the findings that are new, open or regressed (not resolved, not suppressed).
   *
   * @param repository the repository
   * @return count by severity; severities with none may be absent
   */
  Map<Severity, Integer> openBy(String repository);
}
