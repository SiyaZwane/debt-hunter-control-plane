package com.debthunter.controlplane.store.evidence;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/** Keeps the log in memory. For tests and for running without a database. */
public final class InMemoryEvidenceAccessLog implements EvidenceAccessLog {

  private final List<EvidenceAccess> entries = new ArrayList<>();

  @Override
  public synchronized void record(EvidenceAccess access) {
    entries.add(access);
  }

  @Override
  public synchronized List<EvidenceAccess> since(Instant from) {
    return entries.stream().filter(e -> !e.at().isBefore(from)).toList();
  }
}
