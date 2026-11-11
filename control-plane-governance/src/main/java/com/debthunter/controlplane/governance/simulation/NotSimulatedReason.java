package com.debthunter.controlplane.governance.simulation;

/** Why a repository could not be simulated. */
public enum NotSimulatedReason {
  /** No run of any kind in the lookback window. */
  NO_RUNS_IN_WINDOW,
  /**
   * Runs exist, but none is a complete, applied run: all were degraded, scope-limited (such as pull
   * requests), superseded or late. Their findings would understate the repository's debt, so a pass
   * computed from them would be a false comfort.
   */
  NO_COMPLETE_RUN_IN_WINDOW,
  /** The retained findings could not be read. */
  EVIDENCE_UNAVAILABLE,
  /**
   * The candidate requires a history depth and the latest run did not record how deep its history
   * was, so whether it would have passed cannot be said.
   */
  HISTORY_DEPTH_UNKNOWN
}
