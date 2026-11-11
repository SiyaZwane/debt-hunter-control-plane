package com.debthunter.controlplane.store.evidence;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** An {@link EvidenceRepository} held in memory, for tests and local development. */
public final class InMemoryEvidenceRepository implements EvidenceRepository {

  private final List<EvidenceSnapshot> hot = new ArrayList<>();
  private final Map<List<Object>, Integer> warm =
      new TreeMap<>(Comparator.comparing(Object::toString));

  @Override
  public synchronized void saveHot(EvidenceSnapshot snapshot) {
    hot.add(snapshot);
  }

  @Override
  public synchronized List<EvidenceSnapshot> hotForRun(String runId) {
    return hot.stream().filter(s -> s.runId().equals(runId)).toList();
  }

  @Override
  public synchronized int moveExpiredToWarm(Instant asOf) {
    List<EvidenceSnapshot> expired =
        hot.stream().filter(s -> !s.expiresAt().isAfter(asOf)).toList();
    for (EvidenceSnapshot s : expired) {
      warm.merge(
          List.of(
              s.repository(),
              s.ruleId(),
              s.severity(),
              s.capturedAt().atZone(ZoneOffset.UTC).toLocalDate()),
          1,
          Integer::sum);
    }
    hot.removeAll(expired);
    return expired.size();
  }

  @Override
  public synchronized List<EvidenceSummary> warmSummaries(String repository) {
    return warm.entrySet().stream()
        .filter(e -> e.getKey().get(0).equals(repository))
        .map(
            e ->
                new EvidenceSummary(
                    (String) e.getKey().get(0),
                    (String) e.getKey().get(1),
                    (String) e.getKey().get(2),
                    (java.time.LocalDate) e.getKey().get(3),
                    e.getValue()))
        .toList();
  }
}
