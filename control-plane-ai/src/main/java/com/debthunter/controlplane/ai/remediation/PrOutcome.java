package com.debthunter.controlplane.ai.remediation;

import com.debthunter.controlplane.ai.remediation.host.PullRequestRef;

/** What trying to open a proposal pull request did. */
public sealed interface PrOutcome {

  /** Why a pull request was not opened. */
  enum Reason {
    /** The host protects the branch the proposal would be pushed to. Nothing was changed. */
    PROTECTED_BRANCH,
    /** The branch the pull request should target does not exist. Nothing was changed. */
    BASE_BRANCH_MISSING
  }

  /**
   * A pull request exists for the finding.
   *
   * @param pullRequest the pull request
   * @param alreadyOpen true if it had been opened before and nothing new was created
   */
  record Opened(PullRequestRef pullRequest, boolean alreadyOpen) implements PrOutcome {}

  /**
   * No pull request was opened, and nothing on the host was changed.
   *
   * @param reason why
   * @param message a description for the operator
   */
  record Refused(Reason reason, String message) implements PrOutcome {}
}
