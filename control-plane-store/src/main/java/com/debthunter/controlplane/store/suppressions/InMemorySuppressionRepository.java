package com.debthunter.controlplane.store.suppressions;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** A {@link SuppressionRepository} held in memory, for tests and local development. */
public final class InMemorySuppressionRepository implements SuppressionRepository {

  private final Map<String, Suppression> byId = new LinkedHashMap<>();

  @Override
  public synchronized void save(Suppression suppression) {
    byId.values()
        .removeIf(
            s ->
                s.repository().equals(suppression.repository())
                    && s.fingerprint().equals(suppression.fingerprint()));
    byId.put(suppression.id(), suppression);
  }

  @Override
  public synchronized Optional<Suppression> find(String repository, String fingerprint) {
    return byId.values().stream()
        .filter(s -> s.repository().equals(repository) && s.fingerprint().equals(fingerprint))
        .findFirst();
  }

  @Override
  public synchronized boolean delete(String id) {
    return byId.remove(id) != null;
  }

  @Override
  public synchronized List<Suppression> findUnnotified() {
    return byId.values().stream().filter(s -> s.preExpiryNotifiedAt() == null).toList();
  }

  @Override
  public synchronized void markNotified(String id, Instant at) {
    Suppression existing = byId.get(id);
    if (existing != null) {
      byId.put(id, existing.notified(at));
    }
  }
}
