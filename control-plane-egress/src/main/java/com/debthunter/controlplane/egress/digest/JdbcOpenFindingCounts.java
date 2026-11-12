package com.debthunter.controlplane.egress.digest;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import com.debthunter.domain.Severity;
import java.util.EnumMap;
import java.util.Map;
import javax.sql.DataSource;

/**
 * Counts open findings from the system of record: each finding that is new, open or regressed, with
 * the severity it had the last time a run reported it.
 */
public final class JdbcOpenFindingCounts implements OpenFindingCounts {

  private final Jdbc jdbc;

  /**
   * Creates the counter.
   *
   * @param dataSource where connections come from
   */
  public JdbcOpenFindingCounts(DataSource dataSource) {
    this.jdbc = new Jdbc(dataSource);
  }

  @Override
  public Map<Severity, Integer> openBy(String repository) {
    Map<Severity, Integer> counts = new EnumMap<>(Severity.class);
    jdbc.query(
            "SELECT latest.severity AS severity, COUNT(*) AS n"
                + " FROM finding_state s"
                + " JOIN LATERAL ("
                + "   SELECT f.severity FROM finding f JOIN run r ON r.run_id = f.run_id"
                + "   WHERE f.repository = s.repository AND f.fingerprint = s.fingerprint"
                + "   ORDER BY r.applied_seq DESC LIMIT 1) latest ON TRUE"
                + " WHERE s.repository = ? AND s.state IN ('NEW', 'OPEN', 'REGRESSED')"
                + " GROUP BY latest.severity",
            row -> Map.entry(Severity.valueOf(row.getString("severity")), row.getInt("n")),
            repository)
        .forEach(e -> counts.merge(e.getKey(), e.getValue(), Integer::sum));
    return counts;
  }
}
