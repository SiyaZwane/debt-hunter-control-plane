package com.debthunter.controlplane.store.audit;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;

/** Keeps the trail in PostgreSQL ({@code audit_trail}). */
public final class JdbcAuditTrail implements AuditTrail, AuditPurger {

  private final Jdbc jdbc;

  /**
   * Creates the trail.
   *
   * @param dataSource where connections come from
   */
  public JdbcAuditTrail(DataSource dataSource) {
    this.jdbc = new Jdbc(dataSource);
  }

  @Override
  public void record(AuditEntry e) {
    jdbc.update(
        "INSERT INTO audit_trail (kind, recorded_at, caller, action, target, detail, outcome)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
        e.kind().name(),
        e.at(),
        e.caller(),
        e.action(),
        e.target(),
        e.detail(),
        e.outcome().name());
  }

  @Override
  public List<AuditEntry> read(AuditEntry.Kind kind, Instant from, int limit) {
    return jdbc.query(
        "SELECT kind, recorded_at, caller, action, target, detail, outcome FROM audit_trail"
            + " WHERE kind = ? AND recorded_at >= ? ORDER BY id LIMIT ?",
        row ->
            new AuditEntry(
                AuditEntry.Kind.valueOf(row.getString("kind")),
                Jdbc.instant(row, "recorded_at"),
                row.getString("caller"),
                row.getString("action"),
                row.getString("target"),
                row.getString("detail"),
                AuditEntry.Outcome.valueOf(row.getString("outcome"))),
        kind.name(),
        from,
        limit);
  }

  @Override
  public int deleteBefore(Instant cutoff) {
    return jdbc.update("DELETE FROM audit_trail WHERE recorded_at < ?", cutoff);
  }
}
