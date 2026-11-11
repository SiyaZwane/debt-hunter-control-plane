package com.debthunter.controlplane.store.quarantine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A {@link QuarantineRepository} held in memory, for tests and local development. */
public final class InMemoryQuarantineRepository implements QuarantineRepository {

  private final Map<String, QuarantinedBundle> byCorrelation = new LinkedHashMap<>();

  @Override
  public synchronized void save(QuarantinedBundle bundle) {
    byCorrelation.putIfAbsent(bundle.correlationId(), bundle);
  }

  @Override
  public synchronized List<QuarantinedBundle> forRun(String runId) {
    return byCorrelation.values().stream().filter(b -> b.runId().equals(runId)).toList();
  }

  @Override
  public synchronized long count() {
    return byCorrelation.size();
  }
}
