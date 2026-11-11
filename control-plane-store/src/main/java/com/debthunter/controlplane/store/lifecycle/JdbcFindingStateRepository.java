package com.debthunter.controlplane.store.lifecycle;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;

/** {@link FindingStateRepository} over PostgreSQL. */
public final class JdbcFindingStateRepository implements FindingStateRepository {

  private final Jdbc jdbc;

  /**
   * Creates the repository.
   *
   * @param dataSource the database
   */
  public JdbcFindingStateRepository(DataSource dataSource) {
    this.jdbc = new Jdbc(dataSource);
  }

  @Override
  public Map<String, FindingStateRecord> statesFor(String repository, String projectSlice) {
    Map<String, FindingStateRecord> result = new LinkedHashMap<>();
    jdbc.query(
            "SELECT fingerprint, state, last_observed_commit FROM finding_state"
                + " WHERE repository = ? AND project_slice = ?",
            row ->
                new FindingStateRecord(
                    repository,
                    projectSlice,
                    row.getString("fingerprint"),
                    FindingState.valueOf(row.getString("state")),
                    row.getString("last_observed_commit")),
            repository,
            projectSlice)
        .forEach(record -> result.put(record.fingerprint(), record));
    return Map.copyOf(result);
  }

  @Override
  public void putAll(Collection<FindingStateRecord> records) {
    jdbc.inTransaction(
        s -> {
          for (FindingStateRecord record : records) {
            s.update(
                "INSERT INTO finding_state (repository, project_slice, fingerprint, state,"
                    + " last_observed_commit) VALUES (?,?,?,?,?)"
                    + " ON CONFLICT (repository, project_slice, fingerprint) DO UPDATE"
                    + " SET state = EXCLUDED.state,"
                    + " last_observed_commit = EXCLUDED.last_observed_commit",
                record.repository(),
                record.projectSlice(),
                record.fingerprint(),
                record.state().name(),
                record.lastObservedCommit());
          }
          return null;
        });
  }
}
