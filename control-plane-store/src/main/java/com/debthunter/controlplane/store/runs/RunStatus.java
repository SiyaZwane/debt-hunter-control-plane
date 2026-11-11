package com.debthunter.controlplane.store.runs;

/** What became of a run. */
public enum RunStatus {
  /** The current run for its commit; its findings drive lifecycle state. */
  ACTIVE,
  /** Replaced by a later run of the same commit; kept, not deleted. */
  SUPERSEDED,
  /** Arrived after a descendant commit had already been applied; kept for the record only. */
  LATE,
  /** Scope-limited (for example a pull-request diff); kept, but cannot move lifecycle state. */
  SCOPE_LIMITED
}
