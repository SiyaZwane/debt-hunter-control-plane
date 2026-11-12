package com.debthunter.controlplane.ai.guard;

/** What a model is being asked to do, which decides what the guard will send it. */
public enum Purpose {
  /** Explain a finding. May include source code if the repository opted in. */
  EXPLAIN,
  /**
   * Propose next steps for a finding. Never includes source code, whether or not the repository
   * opted in: a remediation proposal is written from the finding alone.
   */
  REMEDIATE
}
