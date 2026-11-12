package com.debthunter.controlplane.store.evidence;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;

/** Keeps the log in PostgreSQL ({@code evidence_access_log}). */
public final class JdbcEvidenceAccessLog implements EvidenceAccessLog {

  private final Jdbc jdbc;

  /**
   * Creates the log.
   *
   * @param dataSource where connections come from
   */
  public JdbcEvidenceAccessLog(DataSource dataSource) {
    this.jdbc = new Jdbc(dataSource);
  }

  @Override
  public void record(EvidenceAccess access) {
    jdbc.update(
        "INSERT INTO evidence_access_log (accessed_at, caller, run_id, repository, purpose,"
            + " outcome) VALUES (?, ?, ?, ?, ?, ?)",
        access.at(),
        access.caller(),
        access.runId(),
        access.repository(),
        access.purpose(),
        access.outcome().name());
  }

  @Override
  public List<EvidenceAccess> since(Instant from) {
    return jdbc.query(
        "SELECT accessed_at, caller, run_id, repository, purpose, outcome FROM evidence_access_log"
            + " WHERE accessed_at >= ? ORDER BY id",
        row ->
            new EvidenceAccess(
                Jdbc.instant(row, "accessed_at"),
                row.getString("caller"),
                row.getString("run_id"),
                row.getString("repository"),
                row.getString("purpose"),
                EvidenceAccess.Outcome.valueOf(row.getString("outcome"))),
        from);
  }
}
