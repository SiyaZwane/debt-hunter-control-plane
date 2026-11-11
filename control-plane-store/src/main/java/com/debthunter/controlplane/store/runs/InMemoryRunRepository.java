package com.debthunter.controlplane.store.runs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A {@link RunRepository} held in memory, for tests and local development. */
public final class InMemoryRunRepository implements RunRepository {

  private final Map<String, RunRecord> byId = new LinkedHashMap<>();
  private final Map<String, String> latestByCommitSlice = new LinkedHashMap<>();
  private final Map<String, Long> seqById = new LinkedHashMap<>();

  @Override
  public synchronized Optional<RunRecord> find(String runId) {
    return Optional.ofNullable(byId.get(runId));
  }

  @Override
  public synchronized List<RunRecord> findByCompositeKey(String compositeKey) {
    return byId.values().stream().filter(r -> r.compositeKey().equals(compositeKey)).toList();
  }

  @Override
  public synchronized Optional<RunRecord> latestApplied(String repository, String projectSlice) {
    String runId = latestByCommitSlice.get(repository + '\u0000' + projectSlice);
    return runId == null ? Optional.empty() : Optional.ofNullable(byId.get(runId));
  }

  @Override
  public synchronized List<SequencedRun> activeAfter(long afterSeq, int limit) {
    return byId.values().stream()
        .filter(r -> r.status() == RunStatus.ACTIVE && seqById.get(r.runId()) > afterSeq)
        .map(r -> new SequencedRun(seqById.get(r.runId()), r))
        .sorted(java.util.Comparator.comparingLong(SequencedRun::seq))
        .limit(limit)
        .toList();
  }

  @Override
  public synchronized List<RunRecord> recentForRepository(String repository, int limit) {
    return byId.values().stream()
        .filter(r -> r.repository().equals(repository))
        .sorted(
            java.util.Comparator.comparingLong((RunRecord r) -> seqById.get(r.runId())).reversed())
        .limit(limit)
        .toList();
  }

  @Override
  public synchronized void save(RunRecord run) {
    if (byId.containsKey(run.runId())) {
      return;
    }
    if (run.status() == RunStatus.ACTIVE) {
      for (RunRecord prior : new ArrayList<>(byId.values())) {
        if (prior.compositeKey().equals(run.compositeKey()) && prior.status() == RunStatus.ACTIVE) {
          byId.put(prior.runId(), prior.supersededBy(run.runId()));
        }
      }
      latestByCommitSlice.put(run.repository() + '\u0000' + run.projectSlice(), run.runId());
    }
    byId.put(run.runId(), run);
    seqById.put(run.runId(), (long) (seqById.size() + 1));
  }
}
