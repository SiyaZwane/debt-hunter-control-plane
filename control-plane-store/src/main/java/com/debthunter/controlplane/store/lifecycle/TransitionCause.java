package com.debthunter.controlplane.store.lifecycle;

/** Why a finding changed state. */
public enum TransitionCause {
  /** Never seen before in this repository and slice. */
  FIRST_OBSERVED,
  /** Seen again at a later commit. */
  OBSERVED_AGAIN,
  /** Absent from a complete run. */
  ABSENT_FROM_COMPLETE_RUN,
  /** Seen again after having been resolved. */
  REAPPEARED_AFTER_RESOLUTION,
  /** An active suppression now covers it. */
  SUPPRESSION_APPLIED,
  /** Its suppression expired as of the commit date. */
  SUPPRESSION_EXPIRED
}
