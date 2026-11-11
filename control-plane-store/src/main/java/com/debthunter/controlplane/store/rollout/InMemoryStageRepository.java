package com.debthunter.controlplane.store.rollout;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** A {@link StageRepository} held in memory, honouring the same contract as the PostgreSQL one. */
public final class InMemoryStageRepository implements StageRepository {

  private final Map<String, StageRecord> records = new TreeMap<>();
  private final List<StageEvent> events = new ArrayList<>();

  @Override
  public synchronized Optional<StageRecord> find(String repository) {
    return Optional.ofNullable(records.get(repository));
  }

  @Override
  public synchronized List<StageRecord> all() {
    return List.copyOf(records.values());
  }

  @Override
  public synchronized Map<String, RolloutStage> currentStages() {
    Map<String, RolloutStage> stages = new TreeMap<>();
    records.forEach((repository, record) -> stages.put(repository, record.stage()));
    return stages;
  }

  @Override
  public synchronized void apply(StageRecord updated, StageEvent event) {
    StageRecord current = records.get(updated.repository());
    if (event.kind() == StageEvent.Kind.ENROLLED) {
      if (current != null) {
        throw new StageConflictException("Already in the rollout: " + updated.repository());
      }
    } else if (current == null || current.stage() != event.fromStage()) {
      throw new StageConflictException(
          updated.repository()
              + " is at "
              + (current == null ? "no stage" : current.stage())
              + ", not "
              + event.fromStage());
    }
    records.put(updated.repository(), updated);
    events.add(event);
  }

  @Override
  public synchronized void update(StageRecord updated) {
    if (!records.containsKey(updated.repository())) {
      throw new StageConflictException("Not in the rollout: " + updated.repository());
    }
    records.put(updated.repository(), updated);
  }

  @Override
  public synchronized List<StageEvent> events(String repository) {
    return events.stream().filter(e -> e.repository().equals(repository)).toList();
  }
}
