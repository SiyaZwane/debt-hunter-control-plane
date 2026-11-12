package com.debthunter.controlplane.store.runs;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/** {@link RunRepository} over PostgreSQL. */
public final class JdbcRunRepository implements RunRepository {

  private static final String COLUMNS =
      "run_id, repository, project_slice, commit_id, tool_version, composite_key, degraded,"
          + " scope_limited, committed_at, status, superseded_by, policy_status, received_at,"
          + " history_depth, policy_bundle_version";

  private final Jdbc jdbc;

  /**
   * Creates the repository.
   *
   * @param dataSource the database
   */
  public JdbcRunRepository(DataSource dataSource) {
    this.jdbc = new Jdbc(dataSource);
  }

  @Override
  public Optional<RunRecord> find(String runId) {
    return jdbc.inTransaction(
        s ->
            s.queryOne(
                "SELECT " + COLUMNS + " FROM run WHERE run_id = ?", row -> map(s, row), runId));
  }

  @Override
  public List<RunRecord> findByCompositeKey(String compositeKey) {
    return jdbc.inTransaction(
        s ->
            s.query(
                "SELECT " + COLUMNS + " FROM run WHERE composite_key = ? ORDER BY applied_seq",
                row -> map(s, row),
                compositeKey));
  }

  @Override
  public Optional<RunRecord> latestApplied(String repository, String projectSlice) {
    return jdbc.inTransaction(
        s ->
            s.queryOne(
                "SELECT "
                    + COLUMNS
                    + " FROM run WHERE repository = ? AND project_slice = ? AND status = 'ACTIVE'"
                    + " ORDER BY applied_seq DESC LIMIT 1",
                row -> map(s, row),
                repository,
                projectSlice));
  }

  @Override
  public List<SequencedRun> activeAfter(long afterSeq, int limit) {
    return jdbc.inTransaction(
        s ->
            s.query(
                "SELECT applied_seq, "
                    + COLUMNS
                    + " FROM run WHERE status = 'ACTIVE' AND applied_seq > ? ORDER BY applied_seq"
                    + " LIMIT ?",
                row -> new SequencedRun(row.getLong("applied_seq"), map(s, row)),
                afterSeq,
                limit));
  }

  @Override
  public List<RunRecord> recentForRepository(String repository, int limit) {
    return jdbc.inTransaction(
        s ->
            s.query(
                "SELECT "
                    + COLUMNS
                    + " FROM run WHERE repository = ? ORDER BY applied_seq DESC LIMIT ?",
                row -> map(s, row),
                repository,
                limit));
  }

  @Override
  public void save(RunRecord run) {
    jdbc.inTransaction(
        s -> {
          boolean exists =
              s.queryOne("SELECT 1 FROM run WHERE run_id = ?", row -> 1, run.runId()).isPresent();
          if (exists) {
            return null;
          }
          if (run.status() == RunStatus.ACTIVE) {
            s.update(
                "UPDATE run SET status = 'SUPERSEDED', superseded_by = ?"
                    + " WHERE composite_key = ? AND status = 'ACTIVE'",
                run.runId(),
                run.compositeKey());
          }
          s.update(
              "INSERT INTO run (run_id, repository, project_slice, commit_id, tool_version,"
                  + " composite_key, degraded, scope_limited, committed_at, status, superseded_by,"
                  + " policy_status, received_at, history_depth, policy_bundle_version)"
                  + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
              run.runId(),
              run.repository(),
              run.projectSlice(),
              run.commit(),
              run.toolVersion(),
              run.compositeKey(),
              run.degraded(),
              run.scopeLimited(),
              run.committedAt(),
              run.status().name(),
              run.supersededBy(),
              run.policyStatus(),
              run.receivedAt(),
              run.historyDepth(),
              run.policyBundleVersion());
          int ordinal = 0;
          for (EngineRecord engine : run.engines()) {
            s.update(
                "INSERT INTO run_engine (run_id, ordinal, engine_id, status) VALUES (?,?,?,?)",
                run.runId(),
                ordinal++,
                engine.id(),
                engine.status());
          }
          return null;
        });
  }

  private static RunRecord map(Jdbc.Session s, java.sql.ResultSet row)
      throws java.sql.SQLException {
    String runId = row.getString("run_id");
    List<EngineRecord> engines =
        s.query(
            "SELECT engine_id, status FROM run_engine WHERE run_id = ? ORDER BY ordinal",
            r -> new EngineRecord(r.getString("engine_id"), r.getString("status")),
            runId);
    return new RunRecord(
        runId,
        row.getString("repository"),
        row.getString("project_slice"),
        row.getString("commit_id"),
        row.getString("tool_version"),
        row.getString("composite_key"),
        row.getBoolean("degraded"),
        row.getBoolean("scope_limited"),
        engines,
        Jdbc.instant(row, "committed_at"),
        RunStatus.valueOf(row.getString("status")),
        row.getString("superseded_by"),
        row.getString("policy_status"),
        Jdbc.instant(row, "received_at"),
        row.getString("history_depth"),
        row.getString("policy_bundle_version"));
  }
}
