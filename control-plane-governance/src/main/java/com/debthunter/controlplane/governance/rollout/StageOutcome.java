package com.debthunter.controlplane.governance.rollout;

import com.debthunter.controlplane.store.rollout.StageRecord;
import java.util.Set;

/** What a request to change a repository's stage did. */
public sealed interface StageOutcome {

  /** Why a change was refused. */
  enum Reason {
    /** The repository is not in the rollout. */
    NOT_ENROLLED,
    /** The repository is already in the rollout. */
    ALREADY_ENROLLED,
    /** Criteria required to leave the stage are missing, or have no evidence. */
    MISSING_CRITERIA,
    /** The repository is at the last stage. */
    NO_NEXT_STAGE,
    /** A regression must go to an earlier stage. */
    NOT_EARLIER,
    /** The change is dated before the repository entered its current stage. */
    BEFORE_CURRENT_STAGE,
    /** Someone else changed the repository first. */
    CONFLICT
  }

  /**
   * The change was made.
   *
   * @param record the repository's record afterwards
   */
  record Changed(StageRecord record) implements StageOutcome {}

  /**
   * The change was refused and nothing was written.
   *
   * @param reason why
   * @param message a human-readable explanation
   * @param missingCriteria for {@link Reason#MISSING_CRITERIA}, which ones
   */
  record Refused(Reason reason, String message, Set<String> missingCriteria)
      implements StageOutcome {

    /** Copies the missing criteria. */
    public Refused {
      missingCriteria = Set.copyOf(missingCriteria);
    }
  }
}
