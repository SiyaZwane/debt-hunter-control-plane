package com.debthunter.controlplane.store.runs;

import java.util.Objects;

/**
 * A run with the position at which it was applied, so a reader can resume from where it stopped.
 *
 * @param seq the position; strictly increasing in the order runs were applied
 * @param run the run
 */
public record SequencedRun(long seq, RunRecord run) {

  /** Validates the components. */
  public SequencedRun {
    Objects.requireNonNull(run, "run");
  }
}
