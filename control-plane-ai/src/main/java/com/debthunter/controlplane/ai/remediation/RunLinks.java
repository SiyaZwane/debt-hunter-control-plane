package com.debthunter.controlplane.ai.remediation;

import java.util.Optional;

/** Where a person can look at a run, if anywhere. */
public interface RunLinks {

  /**
   * A link to a run.
   *
   * @param runId the run
   * @return a URL, or empty if the control plane has no address people can reach
   */
  Optional<String> linkTo(String runId);

  /** No links: the control plane has no address configured. */
  static RunLinks none() {
    return runId -> Optional.empty();
  }
}
