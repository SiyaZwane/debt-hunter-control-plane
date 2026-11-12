package com.debthunter.controlplane.egress.digest;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import java.util.Optional;
import javax.sql.DataSource;

/** Keeps channels in PostgreSQL ({@code squad_channel}). */
public final class JdbcSquadChannelRepository implements SquadChannelRepository {

  private final Jdbc jdbc;

  /**
   * Creates the repository.
   *
   * @param dataSource where connections come from
   */
  public JdbcSquadChannelRepository(DataSource dataSource) {
    this.jdbc = new Jdbc(dataSource);
  }

  @Override
  public void save(SquadChannel channel) {
    jdbc.update(
        "INSERT INTO squad_channel (owner, kind, secret_ref) VALUES (?, ?, ?)"
            + " ON CONFLICT (owner) DO UPDATE SET kind = EXCLUDED.kind,"
            + " secret_ref = EXCLUDED.secret_ref",
        channel.owner(),
        channel.kind().name(),
        channel.secretRef());
  }

  @Override
  public Optional<SquadChannel> find(String owner) {
    return jdbc.queryOne(
        "SELECT owner, kind, secret_ref FROM squad_channel WHERE owner = ?",
        row ->
            new SquadChannel(
                row.getString("owner"),
                SquadChannel.Kind.valueOf(row.getString("kind")),
                row.getString("secret_ref")),
        owner);
  }
}
