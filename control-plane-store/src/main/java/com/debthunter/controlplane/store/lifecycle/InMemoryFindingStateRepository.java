package com.debthunter.controlplane.store.lifecycle;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** A {@link FindingStateRepository} held in memory, for tests and local development. */
public final class InMemoryFindingStateRepository implements FindingStateRepository {

  private final Map<String, Map<String, FindingStateRecord>> bySlice = new ConcurrentHashMap<>();

  @Override
  public Map<String, FindingStateRecord> statesFor(String repository, String projectSlice) {
    return Map.copyOf(bySlice.getOrDefault(key(repository, projectSlice), Map.of()));
  }

  @Override
  public void putAll(Collection<FindingStateRecord> records) {
    for (FindingStateRecord record : records) {
      bySlice
          .computeIfAbsent(
              key(record.repository(), record.projectSlice()), k -> new ConcurrentHashMap<>())
          .put(record.fingerprint(), record);
    }
  }

  private static String key(String repository, String projectSlice) {
    return repository + '\u0000' + projectSlice;
  }

  /** Copy of every record, for tests. */
  public Map<String, FindingStateRecord> snapshot() {
    Map<String, FindingStateRecord> all = new HashMap<>();
    bySlice.forEach((k, v) -> v.forEach((fp, r) -> all.put(k + '\u0000' + fp, r)));
    return all;
  }
}
