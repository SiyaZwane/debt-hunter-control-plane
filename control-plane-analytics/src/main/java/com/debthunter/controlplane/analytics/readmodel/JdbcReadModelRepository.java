package com.debthunter.controlplane.analytics.readmodel;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * {@link ReadModelRepository} over PostgreSQL. A batch and the checkpoints it advances are written
 * in one transaction, so a crash leaves either all of it or none of it.
 */
public final class JdbcReadModelRepository implements ReadModelRepository {

  private final Jdbc jdbc;

  /**
   * Creates the repository.
   *
   * @param dataSource the database
   */
  public JdbcReadModelRepository(DataSource dataSource) {
    this.jdbc = new Jdbc(dataSource);
  }

  @Override
  public long checkpoint(String name) {
    return jdbc.queryOne(
            "SELECT last_seq FROM rm_checkpoint WHERE name = ?",
            row -> row.getLong("last_seq"),
            name)
        .orElse(0L);
  }

  @Override
  public Optional<Instant> lastUpdated() {
    return jdbc.queryOne(
        "SELECT last_updated FROM rm_heartbeat WHERE id = 1",
        row -> Jdbc.instant(row, "last_updated"));
  }

  @Override
  public Optional<OpenFinding> openFinding(
      String repository, String projectSlice, String fingerprint) {
    return jdbc.queryOne(
        "SELECT repository, project_slice, fingerprint, rule_id, severity, category, since"
            + " FROM rm_open_finding WHERE repository = ? AND project_slice = ? AND fingerprint = ?",
        JdbcReadModelRepository::open,
        repository,
        projectSlice,
        fingerprint);
  }

  @Override
  public void commit(ProjectionBatch batch, Instant now) {
    jdbc.inTransaction(
        s -> {
          batch
              .checkpoints()
              .forEach(
                  (name, seq) ->
                      s.update(
                          "INSERT INTO rm_checkpoint (name, last_seq) VALUES (?,?)"
                              + " ON CONFLICT (name) DO UPDATE SET last_seq = EXCLUDED.last_seq",
                          name,
                          seq));
          for (OpenFinding gone : batch.closed()) {
            s.update(
                "DELETE FROM rm_open_finding WHERE repository = ? AND project_slice = ?"
                    + " AND fingerprint = ?",
                gone.repository(),
                gone.projectSlice(),
                gone.fingerprint());
          }
          for (OpenFinding f : batch.opened()) {
            s.update(
                "INSERT INTO rm_open_finding (repository, project_slice, fingerprint, rule_id,"
                    + " severity, category, since) VALUES (?,?,?,?,?,?,?)"
                    + " ON CONFLICT (repository, project_slice, fingerprint) DO UPDATE SET"
                    + " rule_id = EXCLUDED.rule_id, severity = EXCLUDED.severity,"
                    + " category = EXCLUDED.category, since = EXCLUDED.since",
                f.repository(),
                f.projectSlice(),
                f.fingerprint(),
                f.ruleId(),
                f.severity(),
                f.category(),
                f.since());
          }
          for (ResolutionRecord r : batch.resolutions()) {
            s.update(
                "INSERT INTO rm_resolution (repository, project_slice, fingerprint, rule_id,"
                    + " severity, category, resolved_at, duration_seconds) VALUES (?,?,?,?,?,?,?,?)"
                    + " ON CONFLICT DO NOTHING",
                r.repository(),
                r.projectSlice(),
                r.fingerprint(),
                r.ruleId(),
                r.severity(),
                r.category(),
                r.resolvedAt(),
                r.durationSeconds());
          }
          for (RuleDelta d : batch.ruleDeltas()) {
            s.update(
                "INSERT INTO rm_rule_daily (repository, project_slice, rule_id, day, created,"
                    + " resolved, regressed) VALUES (?,?,?,?,?,?,?)"
                    + " ON CONFLICT (repository, project_slice, rule_id, day) DO UPDATE SET"
                    + " created = rm_rule_daily.created + EXCLUDED.created,"
                    + " resolved = rm_rule_daily.resolved + EXCLUDED.resolved,"
                    + " regressed = rm_rule_daily.regressed + EXCLUDED.regressed",
                d.repository(),
                d.projectSlice(),
                d.ruleId(),
                java.sql.Date.valueOf(d.day()),
                d.created(),
                d.resolved(),
                d.regressed());
          }
          for (RunSnapshot snap : batch.snapshots()) {
            s.update(
                "DELETE FROM rm_hotspot WHERE repository = ? AND project_slice = ?"
                    + " AND commit_id = ?",
                snap.repository(),
                snap.projectSlice(),
                snap.commit());
            s.update(
                "INSERT INTO rm_run_snapshot (repository, project_slice, commit_id, run_id,"
                    + " committed_at, critical, high, medium, low, info, architecture_violations,"
                    + " complexity_delta, policy_status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)"
                    + " ON CONFLICT (repository, project_slice, commit_id) DO UPDATE SET"
                    + " run_id = EXCLUDED.run_id, committed_at = EXCLUDED.committed_at,"
                    + " critical = EXCLUDED.critical, high = EXCLUDED.high,"
                    + " medium = EXCLUDED.medium, low = EXCLUDED.low, info = EXCLUDED.info,"
                    + " architecture_violations = EXCLUDED.architecture_violations,"
                    + " complexity_delta = EXCLUDED.complexity_delta,"
                    + " policy_status = EXCLUDED.policy_status",
                snap.repository(),
                snap.projectSlice(),
                snap.commit(),
                snap.runId(),
                snap.committedAt(),
                snap.critical(),
                snap.high(),
                snap.medium(),
                snap.low(),
                snap.info(),
                snap.architectureViolations(),
                snap.complexityDelta(),
                snap.policyStatus());
          }
          for (HotspotPoint h : batch.hotspots()) {
            s.update(
                "INSERT INTO rm_hotspot (repository, project_slice, commit_id, path, committed_at,"
                    + " change_frequency) VALUES (?,?,?,?,?,?)"
                    + " ON CONFLICT (repository, project_slice, commit_id, path) DO UPDATE SET"
                    + " committed_at = EXCLUDED.committed_at,"
                    + " change_frequency = EXCLUDED.change_frequency",
                h.repository(),
                h.projectSlice(),
                h.commit(),
                h.path(),
                h.committedAt(),
                h.changeFrequency());
          }
          s.update(
              "INSERT INTO rm_heartbeat (id, last_updated) VALUES (1, ?)"
                  + " ON CONFLICT (id) DO UPDATE SET last_updated = EXCLUDED.last_updated",
              now);
          return null;
        });
  }

  @Override
  public List<RunSnapshot> latestSnapshots(java.util.Collection<String> repositories) {
    if (repositories.isEmpty()) {
      return List.of();
    }
    String marks = String.join(",", java.util.Collections.nCopies(repositories.size(), "?"));
    return jdbc.query(
        "SELECT DISTINCT ON (repository, project_slice) repository, project_slice, commit_id,"
            + " run_id, committed_at, critical, high, medium, low, info, architecture_violations,"
            + " complexity_delta, policy_status FROM rm_run_snapshot WHERE repository IN ("
            + marks
            + ") ORDER BY repository, project_slice, committed_at DESC, commit_id",
        row ->
            new RunSnapshot(
                row.getString("repository"),
                row.getString("project_slice"),
                row.getString("commit_id"),
                row.getString("run_id"),
                Jdbc.instant(row, "committed_at"),
                row.getInt("critical"),
                row.getInt("high"),
                row.getInt("medium"),
                row.getInt("low"),
                row.getInt("info"),
                row.getInt("architecture_violations"),
                row.getDouble("complexity_delta"),
                row.getString("policy_status")),
        repositories.toArray());
  }

  @Override
  public List<RunSnapshot> snapshots(
      String repository, String projectSlice, Instant from, Instant to) {
    return jdbc.query(
        "SELECT repository, project_slice, commit_id, run_id, committed_at, critical, high, medium,"
            + " low, info, architecture_violations, complexity_delta, policy_status"
            + " FROM rm_run_snapshot WHERE repository = ? AND project_slice = ?"
            + " AND committed_at >= ? AND committed_at < ? ORDER BY committed_at, commit_id",
        row ->
            new RunSnapshot(
                row.getString("repository"),
                row.getString("project_slice"),
                row.getString("commit_id"),
                row.getString("run_id"),
                Jdbc.instant(row, "committed_at"),
                row.getInt("critical"),
                row.getInt("high"),
                row.getInt("medium"),
                row.getInt("low"),
                row.getInt("info"),
                row.getInt("architecture_violations"),
                row.getDouble("complexity_delta"),
                row.getString("policy_status")),
        repository,
        projectSlice,
        from,
        to);
  }

  @Override
  public List<RuleDelta> ruleDeltas(
      String repository, String projectSlice, Instant from, Instant to) {
    return jdbc.query(
        "SELECT repository, project_slice, rule_id, day, created, resolved, regressed"
            + " FROM rm_rule_daily WHERE repository = ? AND project_slice = ?"
            + " AND day >= ? AND day < ? ORDER BY day, rule_id",
        row ->
            new RuleDelta(
                row.getString("repository"),
                row.getString("project_slice"),
                row.getString("rule_id"),
                row.getDate("day").toLocalDate(),
                row.getInt("created"),
                row.getInt("resolved"),
                row.getInt("regressed")),
        repository,
        projectSlice,
        java.sql.Date.valueOf(from.atZone(ZoneOffset.UTC).toLocalDate()),
        java.sql.Date.valueOf(to.atZone(ZoneOffset.UTC).toLocalDate()));
  }

  @Override
  public List<ResolutionRecord> resolutions(
      String repository, String projectSlice, Instant from, Instant to) {
    return jdbc.query(
        "SELECT repository, project_slice, fingerprint, rule_id, severity, category, resolved_at,"
            + " duration_seconds FROM rm_resolution WHERE repository = ? AND project_slice = ?"
            + " AND resolved_at >= ? AND resolved_at < ? ORDER BY resolved_at, fingerprint",
        row ->
            new ResolutionRecord(
                row.getString("repository"),
                row.getString("project_slice"),
                row.getString("fingerprint"),
                row.getString("rule_id"),
                row.getString("severity"),
                row.getString("category"),
                Jdbc.instant(row, "resolved_at"),
                row.getLong("duration_seconds")),
        repository,
        projectSlice,
        from,
        to);
  }

  @Override
  public List<OpenFinding> openFindings(String repository, String projectSlice) {
    return jdbc.query(
        "SELECT repository, project_slice, fingerprint, rule_id, severity, category, since"
            + " FROM rm_open_finding WHERE repository = ? AND project_slice = ?"
            + " ORDER BY since, fingerprint",
        JdbcReadModelRepository::open,
        repository,
        projectSlice);
  }

  @Override
  public List<HotspotPoint> hotspots(
      String repository, String projectSlice, Instant from, Instant to) {
    return jdbc.query(
        "SELECT repository, project_slice, commit_id, path, committed_at, change_frequency"
            + " FROM rm_hotspot WHERE repository = ? AND project_slice = ?"
            + " AND committed_at >= ? AND committed_at < ? ORDER BY committed_at, path",
        row ->
            new HotspotPoint(
                row.getString("repository"),
                row.getString("project_slice"),
                row.getString("commit_id"),
                row.getString("path"),
                Jdbc.instant(row, "committed_at"),
                row.getDouble("change_frequency")),
        repository,
        projectSlice,
        from,
        to);
  }

  private static OpenFinding open(ResultSet row) throws SQLException {
    return new OpenFinding(
        row.getString("repository"),
        row.getString("project_slice"),
        row.getString("fingerprint"),
        row.getString("rule_id"),
        row.getString("severity"),
        row.getString("category"),
        Jdbc.instant(row, "since"));
  }
}
