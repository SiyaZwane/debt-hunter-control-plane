package com.debthunter.controlplane.ingest.receive;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A bounded, in-memory {@link RunBundleDeduplicator} that forgets the least recently seen run once
 * full. Suitable for a single instance; a durable, store-backed implementation replaces it when
 * idempotency is built out.
 */
public final class InMemoryRunBundleDeduplicator implements RunBundleDeduplicator {

  private final Map<String, Boolean> processed;

  /**
   * Creates a deduplicator remembering up to {@code capacity} runs.
   *
   * @param capacity the maximum number of run ids to remember
   */
  public InMemoryRunBundleDeduplicator(int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("capacity must be positive");
    }
    this.processed =
        new LinkedHashMap<>(16, 0.75f, true) {
          private static final long serialVersionUID = 1L;

          @Override
          protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
            return size() > capacity;
          }
        };
  }

  @Override
  public synchronized boolean alreadyProcessed(String runId) {
    return processed.get(runId) != null;
  }

  @Override
  public synchronized void markProcessed(String runId) {
    processed.put(runId, Boolean.TRUE);
  }
}
