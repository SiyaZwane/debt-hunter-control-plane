package com.debthunter.controlplane.egress.workitem;

/** What looking at a finding's work item led to. */
public enum SyncResult {
  /** Nothing needed doing: the item already matches, or the finding has no item to change. */
  NO_ACTION,
  /** A new item was created. */
  CREATED,
  /** An item that already existed in the tracker (left by an interrupted attempt) was adopted. */
  ADOPTED,
  /** The finding's item was reopened. */
  REOPENED,
  /** The finding's item was closed. */
  CLOSED,
  /** The finding's item was put on hold. */
  PUT_ON_HOLD,
  /** No item, because the repository is not yet at the stage where items are created. */
  SKIPPED_BELOW_STAGE,
  /** No item, because the finding is less severe than the threshold. */
  SKIPPED_BELOW_SEVERITY
}
