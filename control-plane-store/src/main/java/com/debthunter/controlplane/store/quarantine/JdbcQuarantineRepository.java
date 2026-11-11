package com.debthunter.controlplane.store.quarantine;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import java.util.List;
import javax.sql.DataSource;

/** {@link QuarantineRepository} over PostgreSQL. */
public final class JdbcQuarantineRepository implements QuarantineRepository {

  private final Jdbc jdbc;

  /**
   * Creates the repository.
   *
   * @param dataSource the database
   */
  public JdbcQuarantineRepository(DataSource dataSource) {
    this.jdbc = new Jdbc(dataSource);
  }

  @Override
  public void save(QuarantinedBundle b) {
    jdbc.update(
        "INSERT INTO quarantined_bundle (correlation_id, run_id, repository, workload_identity,"
            + " schema_version, reason, received_at, quarantined_at) VALUES (?,?,?,?,?,?,?,?)"
            + " ON CONFLICT (correlation_id) DO NOTHING",
        b.correlationId(),
        b.runId(),
        b.repository(),
        b.workloadIdentity(),
        b.schemaVersion(),
        b.reason(),
        b.receivedAt(),
        b.quarantinedAt());
  }

  @Override
  public List<QuarantinedBundle> forRun(String runId) {
    return jdbc.query(
        "SELECT correlation_id, run_id, repository, workload_identity, schema_version, reason,"
            + " received_at, quarantined_at FROM quarantined_bundle WHERE run_id = ?"
            + " ORDER BY quarantined_at, correlation_id",
        row ->
            new QuarantinedBundle(
                row.getString("correlation_id"),
                row.getString("run_id"),
                row.getString("repository"),
                row.getString("workload_identity"),
                row.getString("schema_version"),
                row.getString("reason"),
                Jdbc.instant(row, "received_at"),
                Jdbc.instant(row, "quarantined_at")),
        runId);
  }

  @Override
  public long count() {
    return jdbc.queryOne("SELECT count(*) AS n FROM quarantined_bundle", row -> row.getLong("n"))
        .orElse(0L);
  }
}
