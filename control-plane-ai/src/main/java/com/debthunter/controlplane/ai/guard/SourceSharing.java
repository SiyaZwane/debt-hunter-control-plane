package com.debthunter.controlplane.ai.guard;

/** Whether a repository has agreed to its source code being sent to a model. */
public enum SourceSharing {
  /** The repository explicitly opted in. */
  OPTED_IN,
  /** The repository did not opt in. */
  NOT_OPTED_IN
}
