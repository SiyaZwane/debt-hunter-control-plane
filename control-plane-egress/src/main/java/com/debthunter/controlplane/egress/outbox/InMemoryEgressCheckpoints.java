package com.debthunter.controlplane.egress.outbox;

import java.util.HashMap;
import java.util.Map;

/** Keeps positions in memory. For tests and for running without a database. */
public final class InMemoryEgressCheckpoints implements EgressCheckpoints {

  private final Map<String, Long> positions = new HashMap<>();

  @Override
  public synchronized long read(String name) {
    return positions.getOrDefault(name, 0L);
  }

  @Override
  public synchronized void advance(String name, long seq) {
    positions.merge(name, seq, Math::max);
  }
}
