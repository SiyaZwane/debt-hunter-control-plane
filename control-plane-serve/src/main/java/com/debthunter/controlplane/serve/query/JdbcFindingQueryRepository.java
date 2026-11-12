package com.debthunter.controlplane.serve.query;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Reads findings from PostgreSQL: each finding's current state, joined to the latest run that
 * reported it. Only the columns a reader may see are selected; contributors are never read.
 */
public final class JdbcFindingQueryRepository implements FindingQueryRepository {

  private static final String LATEST =
      " JOIN LATERAL ("
          + "   SELECT f.finding_id, f.rule_id, f.category, f.severity, f.path, f.start_line,"
          + "          f.message, f.run_id, r.committed_at"
          + "   FROM finding f JOIN run r ON r.run_id = f.run_id"
          + "   WHERE f.repository = s.repository AND f.fingerprint = s.fingerprint"
          + "     AND r.project_slice = s.project_slice"
          + "   ORDER BY r.applied_seq DESC LIMIT 1) l ON TRUE";

  private final Jdbc jdbc;

  /**
   * Creates the repository.
   *
   * @param dataSource where connections come from
   */
  public JdbcFindingQueryRepository(DataSource dataSource) {
    this.jdbc = new Jdbc(dataSource);
  }

  @Override
  public Page<FindingRow> find(FindingFilter f, PageRequest page) {
    List<Object> params = new ArrayList<>(f.repositories());
    StringBuilder sql =
        new StringBuilder(
                "SELECT s.repository, s.project_slice, s.fingerprint, s.state, l.finding_id,"
                    + " l.rule_id, l.category, l.severity, l.path, l.start_line, l.message, l.run_id,"
                    + " l.committed_at FROM finding_state s")
            .append(LATEST)
            .append(" WHERE s.repository IN (")
            .append(String.join(",", Collections.nCopies(params.size(), "?")))
            .append(")");
    if (f.severity() != null) {
      sql.append(" AND l.severity = ?");
      params.add(f.severity().name());
    }
    if (f.category() != null) {
      sql.append(" AND l.category = ?");
      params.add(f.category().name());
    }
    if (f.state() != null) {
      sql.append(" AND s.state = ?");
      params.add(f.state().name());
    }
    if (f.observedFrom() != null) {
      sql.append(" AND l.committed_at >= ?");
      params.add(f.observedFrom());
    }
    if (f.observedTo() != null) {
      sql.append(" AND l.committed_at < ?");
      params.add(f.observedTo());
    }
    if (page.cursor() != null) {
      Cursor c = Cursor.decode(page.cursor());
      sql.append(" AND (s.repository, s.project_slice, s.fingerprint) > (?, ?, ?)");
      params.add(c.repository());
      params.add(c.projectSlice());
      params.add(c.fingerprint());
    }
    sql.append(" ORDER BY s.repository, s.project_slice, s.fingerprint LIMIT ?");
    params.add(page.limit() + 1);

    List<FindingRow> rows =
        jdbc.query(sql.toString(), JdbcFindingQueryRepository::map, params.toArray());
    boolean more = rows.size() > page.limit();
    List<FindingRow> items = more ? rows.subList(0, page.limit()) : rows;
    return new Page<>(items, more ? Cursor.after(items.get(items.size() - 1)).encode() : null);
  }

  @Override
  public Map<String, Map<Severity, Integer>> openCounts(Collection<String> repositories) {
    Map<String, Map<Severity, Integer>> counts = new HashMap<>();
    if (repositories.isEmpty()) {
      return counts;
    }
    String marks = String.join(",", Collections.nCopies(repositories.size(), "?"));
    jdbc.query(
            "SELECT s.repository, l.severity, COUNT(*) AS n FROM finding_state s"
                + LATEST
                + " WHERE s.state IN ('NEW', 'OPEN', 'REGRESSED') AND s.repository IN ("
                + marks
                + ") GROUP BY s.repository, l.severity",
            row ->
                new Object[] {
                  row.getString("repository"),
                  Severity.valueOf(row.getString("severity")),
                  row.getInt("n")
                },
            repositories.toArray())
        .forEach(
            r ->
                counts
                    .computeIfAbsent((String) r[0], k -> new EnumMap<>(Severity.class))
                    .merge((Severity) r[1], (Integer) r[2], Integer::sum));
    return counts;
  }

  @Override
  public Optional<Instant> asOf() {
    return jdbc.queryOne(
            "SELECT MAX(received_at) AS latest FROM run",
            row -> Optional.ofNullable(Jdbc.instant(row, "latest")))
        .flatMap(o -> o);
  }

  private static FindingRow map(java.sql.ResultSet row) throws java.sql.SQLException {
    return new FindingRow(
        row.getString("repository"),
        row.getString("project_slice"),
        row.getString("fingerprint"),
        FindingState.valueOf(row.getString("state")),
        row.getString("finding_id"),
        row.getString("rule_id"),
        Category.valueOf(row.getString("category")),
        Severity.valueOf(row.getString("severity")),
        row.getString("path"),
        row.getInt("start_line"),
        row.getString("message"),
        row.getString("run_id"),
        Jdbc.instant(row, "committed_at"));
  }
}
