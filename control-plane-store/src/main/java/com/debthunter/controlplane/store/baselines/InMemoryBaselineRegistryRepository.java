package com.debthunter.controlplane.store.baselines;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** A {@link BaselineRegistryRepository} held in memory, for tests and local development. */
public final class InMemoryBaselineRegistryRepository implements BaselineRegistryRepository {

  private final List<BaselineRecord> records = new ArrayList<>();

  @Override
  public synchronized void save(BaselineRecord baseline) {
    records.removeIf(
        r ->
            r.repository().equals(baseline.repository())
                && r.projectSlice().equals(baseline.projectSlice())
                && r.commit().equals(baseline.commit()));
    records.add(baseline);
  }

  @Override
  public synchronized Optional<BaselineRecord> find(
      String repository, String projectSlice, String commit) {
    return records.stream()
        .filter(
            r ->
                r.repository().equals(repository)
                    && r.projectSlice().equals(projectSlice)
                    && r.commit().equals(commit))
        .findFirst();
  }

  @Override
  public synchronized Optional<BaselineRecord> latest(String repository, String projectSlice) {
    return records.stream()
        .filter(r -> r.repository().equals(repository) && r.projectSlice().equals(projectSlice))
        .max(Comparator.comparing(BaselineRecord::createdAt));
  }
}
