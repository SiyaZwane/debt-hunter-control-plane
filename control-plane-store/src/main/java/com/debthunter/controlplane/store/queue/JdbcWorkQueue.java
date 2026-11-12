package com.debthunter.controlplane.store.queue;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * A {@link WorkQueue} over PostgreSQL.
 *
 * <p>Workers claim with {@code FOR UPDATE SKIP LOCKED}, so several instances can share the queue
 * without waiting on each other or taking the same item. For an ordered kind, an item is only
 * claimable when no earlier item with its key is waiting or in progress.
 */
public final class JdbcWorkQueue implements WorkQueue {

  private static final String CLAIM_ORDERED =
      "WITH candidates AS ("
          + " SELECT q.id FROM work_queue q"
          + " WHERE q.kind = ?"
          + " AND ((q.status = 'PENDING' AND q.not_before <= ?)"
          + "      OR (q.status = 'IN_PROGRESS' AND q.lease_until < ?))"
          + " AND NOT EXISTS (SELECT 1 FROM work_queue e WHERE e.kind = q.kind"
          + "   AND e.queue_key = q.queue_key AND e.id < q.id"
          + "   AND e.status IN ('PENDING', 'IN_PROGRESS'))"
          + " ORDER BY q.id LIMIT ? FOR UPDATE OF q SKIP LOCKED)"
          + " UPDATE work_queue w SET status = 'IN_PROGRESS', leased_by = ?, lease_until = ?,"
          + " attempts = w.attempts + 1 FROM candidates c WHERE w.id = c.id"
          + " RETURNING w.id, w.kind, w.queue_key, w.payload, w.attempts, w.enqueued_at";

  private static final String CLAIM_UNORDERED =
      "WITH candidates AS ("
          + " SELECT q.id FROM work_queue q"
          + " WHERE q.kind = ?"
          + " AND ((q.status = 'PENDING' AND q.not_before <= ?)"
          + "      OR (q.status = 'IN_PROGRESS' AND q.lease_until < ?))"
          + " ORDER BY q.id LIMIT ? FOR UPDATE OF q SKIP LOCKED)"
          + " UPDATE work_queue w SET status = 'IN_PROGRESS', leased_by = ?, lease_until = ?,"
          + " attempts = w.attempts + 1 FROM candidates c WHERE w.id = c.id"
          + " RETURNING w.id, w.kind, w.queue_key, w.payload, w.attempts, w.enqueued_at";

  private final Jdbc jdbc;
  private final Map<String, QueuePolicy> policies;
  private final Clock clock;

  /**
   * Creates the queue.
   *
   * @param dataSource the database
   * @param policies the kinds of work and how each is queued
   * @param clock the source of "now" for delays and leases
   */
  public JdbcWorkQueue(DataSource dataSource, Map<String, QueuePolicy> policies, Clock clock) {
    this.jdbc = new Jdbc(dataSource);
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

  @Override
  public EnqueueResult enqueue(
      String kind, String key, String dedupKey, byte[] payload, Instant notBefore) {
    policy(kind);
    Objects.requireNonNull(key, "key");
    Instant now = clock.instant();
    Instant due = notBefore == null ? now : notBefore;
    return jdbc.inTransaction(
        session -> {
          Optional<Long> inserted =
              session.queryOne(
                  "INSERT INTO work_queue (kind, queue_key, dedup_key, payload, status, enqueued_at,"
                      + " not_before) VALUES (?,?,?,?,'PENDING',?,?)"
                      + " ON CONFLICT (kind, dedup_key) WHERE dedup_key IS NOT NULL"
                      + " AND status IN ('PENDING', 'IN_PROGRESS') DO NOTHING RETURNING id",
                  row -> row.getLong("id"),
                  kind,
                  key,
                  dedupKey,
                  payload,
                  now,
                  due);
          if (inserted.isPresent()) {
            return new EnqueueResult(inserted.get(), false);
          }
          long existing =
              session
                  .queryOne(
                      "SELECT id FROM work_queue WHERE kind = ? AND dedup_key = ?"
                          + " AND status IN ('PENDING', 'IN_PROGRESS')",
                      row -> row.getLong("id"),
                      kind,
                      dedupKey)
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "A duplicate was reported but the original is gone"));
          return new EnqueueResult(existing, true);
        });
  }

  @Override
  public List<QueuedItem> claim(String kind, String worker, int max) {
    QueuePolicy policy = policy(kind);
    Instant now = clock.instant();
    Instant leaseUntil = now.plus(policy.lease());
    List<QueuedItem> claimed =
        new ArrayList<>(
            jdbc.query(
                policy.ordered() ? CLAIM_ORDERED : CLAIM_UNORDERED,
                row ->
                    new QueuedItem(
                        row.getLong("id"),
                        row.getString("kind"),
                        row.getString("queue_key"),
                        row.getBytes("payload"),
                        row.getInt("attempts"),
                        Jdbc.instant(row, "enqueued_at")),
                kind,
                now,
                now,
                max,
                worker,
                leaseUntil));
    claimed.sort(Comparator.comparingLong(QueuedItem::id));
    return claimed;
  }

  @Override
  public boolean complete(long id, String worker) {
    return jdbc.update(
            "UPDATE work_queue SET status = 'DONE', payload = ''::bytea, finished_at = ?,"
                + " lease_until = NULL WHERE id = ? AND status = 'IN_PROGRESS' AND leased_by = ?",
            clock.instant(),
            id,
            worker)
        == 1;
  }

  @Override
  public RetryOutcome retry(long id, String worker, String error) {
    return jdbc.inTransaction(
        session -> {
          Optional<Object[]> held =
              session.queryOne(
                  "SELECT kind, attempts FROM work_queue WHERE id = ? AND status = 'IN_PROGRESS'"
                      + " AND leased_by = ? FOR UPDATE",
                  row -> new Object[] {row.getString("kind"), row.getInt("attempts")},
                  id,
                  worker);
          if (held.isEmpty()) {
            return RetryOutcome.LEASE_LOST;
          }
          QueuePolicy policy = policy((String) held.get()[0]);
          int attempts = (Integer) held.get()[1];
          Instant now = clock.instant();
          if (attempts >= policy.maxAttempts()) {
            session.update(
                "UPDATE work_queue SET status = 'DEAD', last_error = ?, finished_at = ?,"
                    + " lease_until = NULL WHERE id = ?",
                error,
                now,
                id);
            return RetryOutcome.DEAD_LETTERED;
          }
          session.update(
              "UPDATE work_queue SET status = 'PENDING', last_error = ?, not_before = ?,"
                  + " lease_until = NULL WHERE id = ?",
              error,
              now.plus(policy.backoffAfter(attempts)),
              id);
          return RetryOutcome.RETRY_SCHEDULED;
        });
  }

  @Override
  public boolean hold(long id, String worker, String reason) {
    return jdbc.inTransaction(
        session -> {
          Optional<String> kind =
              session.queryOne(
                  "SELECT kind FROM work_queue WHERE id = ? AND status = 'IN_PROGRESS'"
                      + " AND leased_by = ? FOR UPDATE",
                  row -> row.getString("kind"),
                  id,
                  worker);
          if (kind.isEmpty()) {
            return false;
          }
          session.update(
              "UPDATE work_queue SET status = 'PENDING', attempts = GREATEST(attempts - 1, 0),"
                  + " last_error = ?, not_before = ?, lease_until = NULL WHERE id = ?",
              reason,
              clock.instant().plus(policy(kind.get()).maxBackoff()),
              id);
          return true;
        });
  }

  @Override
  public boolean defer(long id, String worker, Instant notBefore) {
    return jdbc.update(
            "UPDATE work_queue SET status = 'PENDING', attempts = GREATEST(attempts - 1, 0),"
                + " not_before = ?, lease_until = NULL WHERE id = ? AND status = 'IN_PROGRESS'"
                + " AND leased_by = ?",
            notBefore,
            id,
            worker)
        == 1;
  }

  @Override
  public int countStarted(String kind, String key, Instant finishedSince, long excludeId) {
    return jdbc.queryOne(
            "SELECT count(*) AS n FROM work_queue WHERE kind = ? AND queue_key = ? AND id <> ?"
                + " AND (status = 'IN_PROGRESS' OR (status = 'DONE' AND finished_at >= ?))",
            row -> row.getInt("n"),
            kind,
            key,
            excludeId,
            finishedSince)
        .orElse(0);
  }

  @Override
  public boolean deadLetter(long id, String worker, String error) {
    return jdbc.update(
            "UPDATE work_queue SET status = 'DEAD', last_error = ?, finished_at = ?,"
                + " lease_until = NULL WHERE id = ? AND status = 'IN_PROGRESS' AND leased_by = ?",
            error,
            clock.instant(),
            id,
            worker)
        == 1;
  }

  @Override
  public int purge(Instant doneBefore, Instant deadBefore) {
    return jdbc.update(
        "DELETE FROM work_queue WHERE (status = 'DONE' AND finished_at < ?)"
            + " OR (status = 'DEAD' AND finished_at < ?)",
        doneBefore,
        deadBefore);
  }

  @Override
  public Optional<QueueEntry> find(long id) {
    return jdbc.queryOne(
        "SELECT id, kind, queue_key, status, attempts, last_error, not_before,"
            + " octet_length(payload) AS payload_bytes FROM work_queue WHERE id = ?",
        row ->
            new QueueEntry(
                row.getLong("id"),
                row.getString("kind"),
                row.getString("queue_key"),
                QueueEntry.Status.valueOf(row.getString("status")),
                row.getInt("attempts"),
                row.getString("last_error"),
                Jdbc.instant(row, "not_before"),
                row.getInt("payload_bytes")),
        id);
  }

  @Override
  public QueueCounts counts(String kind) {
    return jdbc
        .query(
            "SELECT status, count(*) AS n FROM work_queue WHERE kind = ? GROUP BY status",
            row -> new Object[] {row.getString("status"), row.getLong("n")},
            kind)
        .stream()
        .collect(
            java.util.stream.Collectors.collectingAndThen(
                java.util.stream.Collectors.toMap(r -> (String) r[0], r -> (Long) r[1], Long::sum),
                m ->
                    new QueueCounts(
                        m.getOrDefault("PENDING", 0L),
                        m.getOrDefault("IN_PROGRESS", 0L),
                        m.getOrDefault("DONE", 0L),
                        m.getOrDefault("DEAD", 0L))));
  }
}
