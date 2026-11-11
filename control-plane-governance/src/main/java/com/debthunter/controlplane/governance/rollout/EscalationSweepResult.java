package com.debthunter.controlplane.governance.rollout;

import java.util.List;

/**
 * What one escalation sweep did.
 *
 * @param escalated the escalations delivered and recorded
 * @param failed repositories whose escalation could not be delivered; they are retried next sweep
 */
public record EscalationSweepResult(List<StageEscalation> escalated, List<String> failed) {

  /** Copies the lists. */
  public EscalationSweepResult {
    escalated = List.copyOf(escalated);
    failed = List.copyOf(failed);
  }
}
