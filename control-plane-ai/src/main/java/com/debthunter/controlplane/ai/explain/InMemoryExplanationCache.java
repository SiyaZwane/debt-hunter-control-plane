package com.debthunter.controlplane.ai.explain;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** An {@link ExplanationCache} held in memory. */
public final class InMemoryExplanationCache implements ExplanationCache {

  private final Map<Key, Explanation> entries = new ConcurrentHashMap<>();

  @Override
  public Optional<Explanation> get(Key key) {
    return Optional.ofNullable(entries.get(key));
  }

  @Override
  public void put(Key key, Explanation explanation) {
    entries.put(key, explanation);
  }

  /** How many explanations are held. */
  public int size() {
    return entries.size();
  }
}
