package com.debthunter.controlplane.store.lifecycle;

import java.util.Objects;

/**
 * A logged transition with its position in the log, so a reader can resume from where it stopped.
 *
 * @param seq the position; strictly increasing in append order
 * @param event the transition
 */
public record SequencedTransition(long seq, FindingTransitionEvent event) {

  /** Validates the components. */
  public SequencedTransition {
    Objects.requireNonNull(event, "event");
  }
}
