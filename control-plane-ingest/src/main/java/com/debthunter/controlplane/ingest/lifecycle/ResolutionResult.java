package com.debthunter.controlplane.ingest.lifecycle;

import com.debthunter.controlplane.store.lifecycle.FindingStateRecord;
import com.debthunter.controlplane.store.lifecycle.FindingTransitionEvent;
import java.util.List;

/**
 * What applying a run does to a slice's findings.
 *
 * @param events the transitions, in a deterministic order
 * @param updatedStates the new state record of every finding that changed in any way
 */
public record ResolutionResult(
    List<FindingTransitionEvent> events, List<FindingStateRecord> updatedStates) {

  /** Defensively copies the lists. */
  public ResolutionResult {
    events = List.copyOf(events);
    updatedStates = List.copyOf(updatedStates);
  }
}
