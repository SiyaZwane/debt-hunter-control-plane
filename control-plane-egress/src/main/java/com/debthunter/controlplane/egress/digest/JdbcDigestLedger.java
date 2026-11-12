package com.debthunter.controlplane.egress.digest;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/** Keeps the ledger in PostgreSQL ({@code digest_period}, {@code digest_ledger}). */
public final class JdbcDigestLedger implements DigestLedger {

  private final Jdbc jdbc;

  /**
   * Creates the ledger.
   *
   * @param dataSource where connections come from
   */
  public JdbcDigestLedger(DataSource dataSource) {
    this.jdbc = new Jdbc(dataSource);
  }

  @Override
  public Window window(Instant start, Instant end, long latestSeq) {
    return jdbc.inTransaction(
        session -> {
          Optional<Long> previous =
              session.queryOne(
                  "SELECT to_seq FROM digest_period WHERE period_start < ?"
                      + " ORDER BY period_start DESC LIMIT 1",
                  row -> row.getLong("to_seq"),
                  Timestamp.from(start));
          session.update(
              "INSERT INTO digest_period (period_start, period_end, from_seq, to_seq, baseline)"
                  + " VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
              Timestamp.from(start),
              Timestamp.from(end),
              previous.orElse(latestSeq),
              latestSeq,
              previous.isEmpty());
          return session
              .queryOne(
                  "SELECT period_start, period_end, from_seq, to_seq, baseline FROM digest_period"
                      + " WHERE period_start = ?",
                  row ->
                      new Window(
                          Jdbc.instant(row, "period_start"),
                          Jdbc.instant(row, "period_end"),
                          row.getLong("from_seq"),
                          row.getLong("to_seq"),
                          row.getBoolean("baseline")),
                  Timestamp.from(start))
              .orElseThrow();
        });
  }

  @Override
  public boolean record(String owner, Instant periodStart, String payload, Instant now) {
    Timestamp at = payload == null ? Timestamp.from(now) : null;
    return jdbc.update(
            "INSERT INTO digest_ledger (owner, period_start, payload, queued_at, sent_at)"
                + " VALUES (?, ?, ?, ?, ?) ON CONFLICT DO NOTHING",
            owner,
            Timestamp.from(periodStart),
            payload,
            at,
            at)
        == 1;
  }

  @Override
  public Optional<Entry> find(String owner, Instant periodStart) {
    return jdbc.queryOne(
        "SELECT owner, period_start, payload, queued_at, sent_at FROM digest_ledger"
            + " WHERE owner = ? AND period_start = ?",
        JdbcDigestLedger::map,
        owner,
        Timestamp.from(periodStart));
  }

  @Override
  public List<Entry> unqueued() {
    return jdbc.query(
        "SELECT owner, period_start, payload, queued_at, sent_at FROM digest_ledger"
            + " WHERE queued_at IS NULL ORDER BY period_start, owner",
        JdbcDigestLedger::map);
  }

  @Override
  public void markQueued(String owner, Instant periodStart, Instant now) {
    jdbc.update(
        "UPDATE digest_ledger SET queued_at = ? WHERE owner = ? AND period_start = ?",
        Timestamp.from(now),
        owner,
        Timestamp.from(periodStart));
  }

  @Override
  public void markSent(String owner, Instant periodStart, Instant now) {
    jdbc.update(
        "UPDATE digest_ledger SET sent_at = ? WHERE owner = ? AND period_start = ?",
        Timestamp.from(now),
        owner,
        Timestamp.from(periodStart));
  }

  private static Entry map(java.sql.ResultSet row) throws java.sql.SQLException {
    return new Entry(
        row.getString("owner"),
        Jdbc.instant(row, "period_start"),
        row.getString("payload"),
        Jdbc.instant(row, "queued_at"),
        Jdbc.instant(row, "sent_at"));
  }
}
