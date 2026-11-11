package com.debthunter.controlplane.store.baselines;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import java.util.Optional;
import javax.sql.DataSource;

/** {@link BaselineRegistryRepository} over PostgreSQL. */
public final class JdbcBaselineRegistryRepository implements BaselineRegistryRepository {

  private static final String COLUMNS =
      "repository, project_slice, commit_id, oci_reference, digest, created_at, provenance";

  private final Jdbc jdbc;

  /**
   * Creates the repository.
   *
   * @param dataSource the database
   */
  public JdbcBaselineRegistryRepository(DataSource dataSource) {
    this.jdbc = new Jdbc(dataSource);
  }

  @Override
  public void save(BaselineRecord b) {
    jdbc.update(
        "INSERT INTO baseline ("
            + COLUMNS
            + ") VALUES (?,?,?,?,?,?,?)"
            + " ON CONFLICT (repository, project_slice, commit_id) DO UPDATE SET"
            + " oci_reference = EXCLUDED.oci_reference, digest = EXCLUDED.digest,"
            + " created_at = EXCLUDED.created_at, provenance = EXCLUDED.provenance",
        b.repository(),
        b.projectSlice(),
        b.commit(),
        b.ociReference(),
        b.digest(),
        b.createdAt(),
        b.provenance());
  }

  @Override
  public Optional<BaselineRecord> find(String repository, String projectSlice, String commit) {
    return jdbc.queryOne(
        "SELECT "
            + COLUMNS
            + " FROM baseline WHERE repository = ? AND project_slice = ?"
            + " AND commit_id = ?",
        JdbcBaselineRegistryRepository::map,
        repository,
        projectSlice,
        commit);
  }

  @Override
  public Optional<BaselineRecord> latest(String repository, String projectSlice) {
    return jdbc.queryOne(
        "SELECT "
            + COLUMNS
            + " FROM baseline WHERE repository = ? AND project_slice = ?"
            + " ORDER BY created_at DESC LIMIT 1",
        JdbcBaselineRegistryRepository::map,
        repository,
        projectSlice);
  }

  private static BaselineRecord map(java.sql.ResultSet row) throws java.sql.SQLException {
    return new BaselineRecord(
        row.getString("repository"),
        row.getString("project_slice"),
        row.getString("commit_id"),
        row.getString("oci_reference"),
        row.getString("digest"),
        Jdbc.instant(row, "created_at"),
        row.getString("provenance"));
  }
}
