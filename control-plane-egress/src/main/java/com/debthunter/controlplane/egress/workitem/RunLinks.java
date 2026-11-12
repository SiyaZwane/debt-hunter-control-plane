package com.debthunter.controlplane.egress.workitem;

import java.util.Optional;

/** Where a person can look at a run, if anywhere. */
@FunctionalInterface
public interface RunLinks {

  /**
   * The address of a run.
   *
   * @param runId the run
   * @return a link, or empty if none is configured
   */
  Optional<String> linkTo(String runId);

  /** No links. */
  static RunLinks none() {
    return runId -> Optional.empty();
  }
}
