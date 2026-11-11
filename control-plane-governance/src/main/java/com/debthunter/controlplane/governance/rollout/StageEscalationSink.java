package com.debthunter.controlplane.governance.rollout;

/**
 * Where escalations go. Nothing a pipeline waits on depends on it: escalation is a background
 * sweep.
 *
 * <p>Failure contract: {@code emit} throws if the escalation was not delivered. The sweep then
 * leaves the repository unmarked and tries again next time, so delivery is at-least-once.
 */
public interface StageEscalationSink {

  /**
   * Delivers an escalation.
   *
   * @param escalation what to deliver
   */
  void emit(StageEscalation escalation);
}
