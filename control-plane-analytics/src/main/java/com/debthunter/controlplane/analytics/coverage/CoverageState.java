package com.debthunter.controlplane.analytics.coverage;

/** Where a repository stands with respect to the control plane. Exactly one applies to each. */
public enum CoverageState {
  /** Onboarded, reported recently, with usable data from a current tool. */
  REPORTING,
  /**
   * Onboarded, but no run has been received within the cadence window (or ever). Silence is a
   * finding: a repository that says nothing looks exactly like a clean one.
   */
  SILENT,
  /** In the source-control estate but absent from the registry: nobody is watching it. */
  NEVER_ONBOARDED,
  /** Reporting, but its recent runs are consistently degraded or analysed with shallow history. */
  DEGRADED_REPORTING,
  /** Reporting, but with a tool version that is not the current major version. */
  VERSION_DRIFT;

  /** Whether the repository is covered with trustworthy, current data. */
  public boolean isHealthy() {
    return this == REPORTING;
  }
}
