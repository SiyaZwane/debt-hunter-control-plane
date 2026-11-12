package com.debthunter.controlplane.ai.remediation;

/** What asking for a remediation proposal did. */
public sealed interface RequestOutcome {

  /** Why a request was refused. */
  enum Reason {
    /** The repository has not opted in to remediation pull requests. */
    NOT_ENABLED,
    /** The run or finding does not exist for that repository. */
    NOT_FOUND,
    /** The base branch name is not acceptable. */
    INVALID_BASE_BRANCH,
    /** The opt-in could not be checked, so the request was not accepted. */
    ENABLEMENT_UNAVAILABLE
  }

  /**
   * The request is on the queue.
   *
   * @param queueId its id
   * @param alreadyQueued true if an identical request was already waiting, so nothing was added
   */
  record Queued(long queueId, boolean alreadyQueued) implements RequestOutcome {}

  /**
   * The request was refused and nothing was queued.
   *
   * @param reason why
   */
  record Refused(Reason reason) implements RequestOutcome {}
}
