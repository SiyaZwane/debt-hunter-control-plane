package com.debthunter.controlplane.store.findings;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A {@link PseudonymisedFindingStore} held in memory, for tests and local development. */
public final class InMemoryPseudonymisedFindingStore implements PseudonymisedFindingStore {

  private final Map<String, List<PseudonymisedFinding>> byRun = new ConcurrentHashMap<>();

  @Override
  public void saveRun(String runId, String repository, List<PseudonymisedFinding> findings) {
    byRun.put(runId, List.copyOf(findings));
  }

  @Override
  public List<PseudonymisedFinding> findingsForRun(String runId) {
    return byRun.getOrDefault(runId, List.of());
  }
}
