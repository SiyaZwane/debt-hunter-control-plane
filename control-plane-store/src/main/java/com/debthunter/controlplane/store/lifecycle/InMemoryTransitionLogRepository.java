package com.debthunter.controlplane.store.lifecycle;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** A {@link TransitionLogRepository} held in memory, for tests and local development. */
public final class InMemoryTransitionLogRepository implements TransitionLogRepository {

  private final List<FindingTransitionEvent> log = new ArrayList<>();
  private final Set<String> ids = new HashSet<>();

  @Override
  public synchronized void append(List<FindingTransitionEvent> events) {
    for (FindingTransitionEvent event : events) {
      if (ids.add(event.eventId())) {
        log.add(event);
      }
    }
  }

  @Override
  public synchronized List<FindingTransitionEvent> historyOf(
      String repository, String projectSlice, String fingerprint) {
    return log.stream()
        .filter(
            e ->
                e.repository().equals(repository)
                    && e.projectSlice().equals(projectSlice)
                    && e.fingerprint().equals(fingerprint))
        .toList();
  }

  @Override
  public synchronized List<FindingTransitionEvent> eventsOfRun(String runId) {
    return log.stream().filter(e -> e.runId().equals(runId)).toList();
  }
}
