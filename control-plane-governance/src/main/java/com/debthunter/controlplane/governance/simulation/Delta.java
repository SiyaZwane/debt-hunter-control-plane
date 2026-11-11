package com.debthunter.controlplane.governance.simulation;

/** How a repository's verdict changes between its current policy and the candidate. */
public enum Delta {
  /** Passes today, would fail under the candidate. */
  NEWLY_FAILING,
  /** Fails today, would pass under the candidate. */
  NEWLY_PASSING,
  /** Passes under both. */
  UNCHANGED_PASSING,
  /** Fails under both. */
  UNCHANGED_FAILING,
  /** The current policy's content or evidence is not available, so no comparison can be made. */
  UNKNOWN
}
