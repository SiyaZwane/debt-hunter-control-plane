package com.debthunter.controlplane.store.suppressions;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/** {@link SuppressionRepository} over PostgreSQL. */
public final class JdbcSuppressionRepository implements SuppressionRepository {

  private static final String COLUMNS =
      "suppression_id, repository, fingerprint, reason, created_at, expires_at, max_expiry_days,"
          + " pre_expiry_notified_at";

  private final Jdbc jdbc;

  /**
   * Creates the repository.
   *
   * @param dataSource the database
   */
  public JdbcSuppressionRepository(DataSource dataSource) {
    this.jdbc = new Jdbc(dataSource);
  }

  @Override
  public void save(Suppression s) {
    jdbc.inTransaction(
        tx -> {
          tx.update(
              "DELETE FROM suppression WHERE repository = ? AND fingerprint = ?",
              s.repository(),
              s.fingerprint());
          tx.update(
              "INSERT INTO suppression (" + COLUMNS + ") VALUES (?,?,?,?,?,?,?,?)",
              s.id(),
              s.repository(),
              s.fingerprint(),
              s.reason(),
              s.createdAt(),
              s.expiresAt(),
              s.maxExpiryDays(),
              s.preExpiryNotifiedAt());
          return null;
        });
  }

  @Override
  public Optional<Suppression> find(String repository, String fingerprint) {
    return jdbc.queryOne(
        "SELECT " + COLUMNS + " FROM suppression WHERE repository = ? AND fingerprint = ?",
        JdbcSuppressionRepository::map,
        repository,
        fingerprint);
  }

  @Override
  public boolean delete(String id) {
    return jdbc.update("DELETE FROM suppression WHERE suppression_id = ?", id) > 0;
  }

  @Override
  public List<Suppression> findExpiring(Instant from, Instant to) {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM suppression WHERE expires_at >= ? AND expires_at < ?"
            + " ORDER BY expires_at, suppression_id",
        JdbcSuppressionRepository::map,
        from,
        to);
  }

  @Override
  public List<Suppression> findUnnotified() {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM suppression WHERE pre_expiry_notified_at IS NULL"
            + " ORDER BY created_at, suppression_id",
        JdbcSuppressionRepository::map);
  }

  @Override
  public void markNotified(String id, Instant at) {
    jdbc.update(
        "UPDATE suppression SET pre_expiry_notified_at = ? WHERE suppression_id = ?", at, id);
  }

  private static Suppression map(java.sql.ResultSet row) throws java.sql.SQLException {
    return new Suppression(
        row.getString("suppression_id"),
        row.getString("repository"),
        row.getString("fingerprint"),
        row.getString("reason"),
        Jdbc.instant(row, "created_at"),
        Jdbc.instant(row, "expires_at"),
        row.getInt("max_expiry_days"),
        Jdbc.instant(row, "pre_expiry_notified_at"));
  }
}
