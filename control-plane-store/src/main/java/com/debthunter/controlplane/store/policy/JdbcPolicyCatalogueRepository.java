package com.debthunter.controlplane.store.policy;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/** {@link PolicyCatalogueRepository} over PostgreSQL. */
public final class JdbcPolicyCatalogueRepository implements PolicyCatalogueRepository {

  private static final String COLUMNS =
      "version, digest, ratified_by, ratified_at, effective_from, superseded_by";

  private final Jdbc jdbc;

  /**
   * Creates the repository.
   *
   * @param dataSource the database
   */
  public JdbcPolicyCatalogueRepository(DataSource dataSource) {
    this.jdbc = new Jdbc(dataSource);
  }

  @Override
  public void save(PolicyBundleRecord b) {
    jdbc.update(
        "INSERT INTO policy_bundle ("
            + COLUMNS
            + ") VALUES (?,?,?,?,?,?)"
            + " ON CONFLICT (version) DO UPDATE SET digest = EXCLUDED.digest,"
            + " ratified_by = EXCLUDED.ratified_by, ratified_at = EXCLUDED.ratified_at,"
            + " effective_from = EXCLUDED.effective_from, superseded_by = EXCLUDED.superseded_by",
        b.version(),
        b.digest(),
        b.ratifiedBy(),
        b.ratifiedAt(),
        b.effectiveFrom(),
        b.supersededBy());
  }

  @Override
  public Optional<PolicyBundleRecord> find(String version) {
    return jdbc.queryOne(
        "SELECT " + COLUMNS + " FROM policy_bundle WHERE version = ?",
        JdbcPolicyCatalogueRepository::map,
        version);
  }

  @Override
  public List<PolicyBundleRecord> all() {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM policy_bundle ORDER BY version",
        JdbcPolicyCatalogueRepository::map);
  }

  private static PolicyBundleRecord map(java.sql.ResultSet row) throws java.sql.SQLException {
    return new PolicyBundleRecord(
        row.getString("version"),
        row.getString("digest"),
        row.getString("ratified_by"),
        Jdbc.instant(row, "ratified_at"),
        Jdbc.instant(row, "effective_from"),
        row.getString("superseded_by"));
  }
}
