package com.debthunter.controlplane.egress.workitem;

/** The three states the control plane puts a work item in, whatever a tracker calls them. */
public enum WorkItemStatus {
  /** The finding is open: someone should be working on it. */
  OPEN,
  /** The finding is suppressed for now. */
  ON_HOLD,
  /** The finding is resolved. */
  CLOSED
}
