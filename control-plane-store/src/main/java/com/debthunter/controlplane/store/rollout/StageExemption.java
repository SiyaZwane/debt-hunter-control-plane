package com.debthunter.controlplane.store.rollout;

import java.time.Instant;

/**
 * An approved exemption from the maximum hold period at a stage: a repository allowed to stay put
 * for now, by someone accountable, until a date.
 *
 * @param reason why the repository may stay
 * @param approvedBy who approved it
 * @param grantedAt when
 * @param expiresAt when it stops excusing the hold
 */
public record StageExemption(
    String reason, String approvedBy, Instant grantedAt, Instant expiresAt) {

  /** Validates the components. */
  public StageExemption {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("An exception needs a reason");
    }
    if (approvedBy == null || approvedBy.isBlank()) {
      throw new IllegalArgumentException("An exception needs an approver");
    }
    if (grantedAt == null || expiresAt == null || !expiresAt.isAfter(grantedAt)) {
      throw new IllegalArgumentException("An exception must expire after it was granted");
    }
  }

  /** Whether it still excuses the hold at {@code now}. */
  public boolean isActiveAt(Instant now) {
    return now.isBefore(expiresAt);
  }
}
