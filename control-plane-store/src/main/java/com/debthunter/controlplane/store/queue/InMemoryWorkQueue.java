package com.debthunter.controlplane.store.queue;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** A {@link WorkQueue} held in memory, honouring the same contract as the PostgreSQL one. */
public final class InMemoryWorkQueue implements WorkQueue {

  private static final class Row {
    long id;
    String kind;
    String key;
    String dedupKey;
    byte[] payload;
    QueueEntry.Status status;
    int attempts;
    Instant enqueuedAt;
    Instant notBefore;
    Instant leaseUntil;
    String leasedBy;
    String lastError;
    Instant finishedAt;
  }

  private final Map<String, QueuePolicy> policies;
  private final Clock clock;
  private final List<Row> rows = new ArrayList<>();
  private long nextId = 1;

  /**
   * Creates the queue.
   *
   * @param policies the kinds of work and how each is queued
   * @param clock the source of "now" for delays and leases
   */
  public InMemoryWorkQueue(Map<String, QueuePolicy> policies, Clock clock) {
    this.policies = Map.copyOf(policies);
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  private QueuePolicy policy(String kind) {
    QueuePolicy policy = policies.get(kind);
    if (policy == null) {
      throw new IllegalArgumentException("Unknown queue kind: " + kind);
    }
    return policy;
  }

  private static boolean active(Row r) {
    return r.status == QueueEntry.Status.PENDING || r.status == QueueEntry.Status.IN_PROGRESS;
  }

  @Override
  public synchronized EnqueueResult enqueue(
      String kind, String key, String dedupKey, byte[] payload, Instant notBefore) {
    policy(kind);
    Objects.requireNonNull(key, "key");
    if (dedupKey != null) {
      for (Row r : rows) {
        if (r.kind.equals(kind) && dedupKey.equals(r.dedupKey) && active(r)) {
          return new EnqueueResult(r.id, true);
        }
      }
    }
    Instant now = clock.instant();
    Row row = new Row();
    row.id = nextId++;
    row.kind = kind;
    row.key = key;
    row.dedupKey = dedupKey;
    row.payload = Arrays.copyOf(payload, payload.length);
    row.status = QueueEntry.Status.PENDING;
    row.enqueuedAt = now;
    row.notBefore = notBefore == null ? now : notBefore;
    rows.add(row);
    return new EnqueueResult(row.id, false);
  }

  @Override
  public synchronized List<QueuedItem> claim(String kind, String worker, int max) {
    QueuePolicy policy = policy(kind);
    Instant now = clock.instant();
    List<QueuedItem> claimed = new ArrayList<>();
    for (Row r : rows) {
      if (claimed.size() >= max) {
        break;
      }
      if (!r.kind.equals(kind)) {
        continue;
      }
      boolean due =
          (r.status == QueueEntry.Status.PENDING && !r.notBefore.isAfter(now))
              || (r.status == QueueEntry.Status.IN_PROGRESS && r.leaseUntil.isBefore(now));
      if (!due) {
        continue;
      }
      if (policy.ordered() && hasEarlierActive(r)) {
        continue;
      }
      r.status = QueueEntry.Status.IN_PROGRESS;
      r.leasedBy = worker;
      r.leaseUntil = now.plus(policy.lease());
      r.attempts++;
      claimed.add(new QueuedItem(r.id, r.kind, r.key, r.payload, r.attempts, r.enqueuedAt));
    }
    return claimed;
  }

  private boolean hasEarlierActive(Row row) {
    for (Row e : rows) {
      if (e.id < row.id && e.kind.equals(row.kind) && e.key.equals(row.key) && active(e)) {
        return true;
      }
    }
    return false;
  }

  private Row held(long id, String worker) {
    for (Row r : rows) {
      if (r.id == id) {
        return r.status == QueueEntry.Status.IN_PROGRESS && worker.equals(r.leasedBy) ? r : null;
      }
    }
    return null;
  }

  @Override
  public synchronized boolean complete(long id, String worker) {
    Row r = held(id, worker);
    if (r == null) {
      return false;
    }
    r.status = QueueEntry.Status.DONE;
    r.payload = new byte[0];
    r.finishedAt = clock.instant();
    r.leaseUntil = null;
    return true;
  }

  @Override
  public synchronized RetryOutcome retry(long id, String worker, String error) {
    Row r = held(id, worker);
    if (r == null) {
      return RetryOutcome.LEASE_LOST;
    }
    QueuePolicy policy = policy(r.kind);
    r.lastError = error;
    r.leaseUntil = null;
    if (r.attempts >= policy.maxAttempts()) {
      r.status = QueueEntry.Status.DEAD;
      r.finishedAt = clock.instant();
      return RetryOutcome.DEAD_LETTERED;
    }
    r.status = QueueEntry.Status.PENDING;
    r.notBefore = clock.instant().plus(policy.backoffAfter(r.attempts));
    return RetryOutcome.RETRY_SCHEDULED;
  }

  @Override
  public synchronized boolean hold(long id, String worker, String reason) {
    Row r = held(id, worker);
    if (r == null) {
      return false;
    }
    r.status = QueueEntry.Status.PENDING;
    r.attempts = Math.max(0, r.attempts - 1);
    r.lastError = reason;
    r.leaseUntil = null;
    r.notBefore = clock.instant().plus(policy(r.kind).maxBackoff());
    return true;
  }

  @Override
  public synchronized boolean deadLetter(long id, String worker, String error) {
    Row r = held(id, worker);
    if (r == null) {
      return false;
    }
    r.status = QueueEntry.Status.DEAD;
    r.lastError = error;
    r.finishedAt = clock.instant();
    r.leaseUntil = null;
    return true;
  }

  @Override
  public synchronized int purge(Instant doneBefore, Instant deadBefore) {
    int before = rows.size();
    rows.removeIf(
        r ->
            (r.status == QueueEntry.Status.DONE && r.finishedAt.isBefore(doneBefore))
                || (r.status == QueueEntry.Status.DEAD && r.finishedAt.isBefore(deadBefore)));
    return before - rows.size();
  }

  @Override
  public synchronized Optional<QueueEntry> find(long id) {
    for (Row r : rows) {
      if (r.id == id) {
        return Optional.of(
            new QueueEntry(
                r.id,
                r.kind,
                r.key,
                r.status,
                r.attempts,
                r.lastError,
                r.notBefore,
                r.payload.length));
      }
    }
    return Optional.empty();
  }

  @Override
  public synchronized QueueCounts counts(String kind) {
    long pending = 0;
    long inProgress = 0;
    long done = 0;
    long dead = 0;
    for (Row r : rows) {
      if (!r.kind.equals(kind)) {
        continue;
      }
      switch (r.status) {
        case PENDING -> pending++;
        case IN_PROGRESS -> inProgress++;
        case DONE -> done++;
        case DEAD -> dead++;
      }
    }
    return new QueueCounts(pending, inProgress, done, dead);
  }
}
