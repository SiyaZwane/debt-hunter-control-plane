package com.debthunter.controlplane.egress.digest;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** Keeps the ledger in memory. For tests and for running without a database. */
public final class InMemoryDigestLedger implements DigestLedger {

  private final Map<Instant, Window> windows = new TreeMap<>();
  private final Map<String, Entry> entries = new TreeMap<>();

  private static String id(String owner, Instant start) {
    return owner + '\u0000' + start;
  }

  @Override
  public synchronized Window window(Instant start, Instant end, long latestSeq) {
    Window existing = windows.get(start);
    if (existing != null) {
      return existing;
    }
    long from = latestSeq;
    boolean baseline = true;
    for (Window w : windows.values()) {
      if (w.start().isBefore(start)) {
        from = w.toSeq();
        baseline = false;
      }
    }
    Window created = new Window(start, end, from, latestSeq, baseline);
    windows.put(start, created);
    return created;
  }

  @Override
  public synchronized boolean record(
      String owner, Instant periodStart, String payload, Instant now) {
    String id = id(owner, periodStart);
    if (entries.containsKey(id)) {
      return false;
    }
    entries.put(
        id,
        payload == null
            ? new Entry(owner, periodStart, null, now, now)
            : new Entry(owner, periodStart, payload, null, null));
    return true;
  }

  @Override
  public synchronized Optional<Entry> find(String owner, Instant periodStart) {
    return Optional.ofNullable(entries.get(id(owner, periodStart)));
  }

  @Override
  public synchronized List<Entry> unqueued() {
    return new ArrayList<>(entries.values().stream().filter(e -> e.queuedAt() == null).toList());
  }

  @Override
  public synchronized void markQueued(String owner, Instant periodStart, Instant now) {
    entries.computeIfPresent(
        id(owner, periodStart),
        (k, e) -> new Entry(e.owner(), e.periodStart(), e.payload(), now, e.sentAt()));
  }

  @Override
  public synchronized void markSent(String owner, Instant periodStart, Instant now) {
    entries.computeIfPresent(
        id(owner, periodStart),
        (k, e) -> new Entry(e.owner(), e.periodStart(), e.payload(), e.queuedAt(), now));
  }
}
