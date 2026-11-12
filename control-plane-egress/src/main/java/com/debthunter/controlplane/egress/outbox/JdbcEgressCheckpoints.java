package com.debthunter.controlplane.egress.outbox;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import javax.sql.DataSource;

/** Keeps positions in PostgreSQL ({@code egress_checkpoint}). */
public final class JdbcEgressCheckpoints implements EgressCheckpoints {

  private final Jdbc jdbc;

  /**
   * Creates the checkpoints.
   *
   * @param dataSource where connections come from
   */
  public JdbcEgressCheckpoints(DataSource dataSource) {
    this.jdbc = new Jdbc(dataSource);
  }

  @Override
  public long read(String name) {
    return jdbc.queryOne(
            "SELECT last_seq FROM egress_checkpoint WHERE name = ?",
            row -> row.getLong("last_seq"),
            name)
        .orElse(0L);
  }

  @Override
  public void advance(String name, long seq) {
    jdbc.update(
        "INSERT INTO egress_checkpoint (name, last_seq) VALUES (?, ?)"
            + " ON CONFLICT (name) DO UPDATE SET last_seq = GREATEST(egress_checkpoint.last_seq,"
            + " EXCLUDED.last_seq)",
        name,
        seq);
  }
}
