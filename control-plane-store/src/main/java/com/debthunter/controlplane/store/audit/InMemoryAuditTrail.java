package com.debthunter.controlplane.store.audit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Keeps the trail in memory, for tests and for running without a database. */
public final class InMemoryAuditTrail implements AuditTrail, AuditPurger {

  private final List<AuditEntry> entries = new ArrayList<>();

  @Override
  public synchronized void record(AuditEntry entry) {
    entries.add(Objects.requireNonNull(entry, "entry"));
  }

  @Override
  public synchronized List<AuditEntry> read(AuditEntry.Kind kind, Instant from, int limit) {
    return entries.stream()
        .filter(e -> e.kind() == kind && !e.at().isBefore(from))
        .limit(limit)
        .toList();
  }

  @Override
  public synchronized int deleteBefore(Instant cutoff) {
    int before = entries.size();
    entries.removeIf(e -> e.at().isBefore(cutoff));
    return before - entries.size();
  }
}
