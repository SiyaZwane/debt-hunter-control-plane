package com.debthunter.controlplane.store.evidence;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import com.debthunter.controlplane.store.jdbc.StoreException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/** {@link EvidenceRepository} over PostgreSQL. */
public final class JdbcEvidenceRepository implements EvidenceRepository {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final Jdbc jdbc;

  /**
   * Creates the repository.
   *
   * @param dataSource the database
   */
  public JdbcEvidenceRepository(DataSource dataSource) {
    this.jdbc = new Jdbc(dataSource);
  }

  @Override
  public void saveHot(EvidenceSnapshot s) {
    jdbc.update(
        "INSERT INTO evidence_hot (snapshot_id, run_id, repository, finding_id, fingerprint,"
            + " rule_id, severity, evidence, captured_at, expires_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
        s.id(),
        s.runId(),
        s.repository(),
        s.findingId(),
        s.fingerprint(),
        s.ruleId(),
        s.severity(),
        write(s.evidence()),
        s.capturedAt(),
        s.expiresAt());
  }

  @Override
  public List<EvidenceSnapshot> hotForRun(String runId) {
    return jdbc.query(
        "SELECT snapshot_id, run_id, repository, finding_id, fingerprint, rule_id, severity,"
            + " evidence, captured_at, expires_at FROM evidence_hot WHERE run_id = ?"
            + " ORDER BY captured_at, snapshot_id",
        row ->
            EvidenceSnapshot.restore(
                row.getString("snapshot_id"),
                row.getString("run_id"),
                row.getString("repository"),
                row.getString("finding_id"),
                row.getString("fingerprint"),
                row.getString("rule_id"),
                row.getString("severity"),
                read(row.getString("evidence")),
                Jdbc.instant(row, "captured_at"),
                Jdbc.instant(row, "expires_at")),
        runId);
  }

  @Override
  public int moveExpiredToWarm(Instant asOf) {
    return jdbc.inTransaction(
        s -> {
          // DELETE ... RETURNING hands back exactly the rows removed, so each is counted once.
          List<Object[]> gone =
              s.query(
                  "DELETE FROM evidence_hot WHERE expires_at <= ?"
                      + " RETURNING repository, rule_id, severity, captured_at",
                  row ->
                      new Object[] {
                        row.getString("repository"),
                        row.getString("rule_id"),
                        row.getString("severity"),
                        Jdbc.instant(row, "captured_at")
                      },
                  asOf);
          Map<List<Object>, Integer> counts = new LinkedHashMap<>();
          for (Object[] g : gone) {
            LocalDate day = ((Instant) g[3]).atZone(ZoneOffset.UTC).toLocalDate();
            counts.merge(List.of(g[0], g[1], g[2], day), 1, Integer::sum);
          }
          counts.forEach(
              (key, count) ->
                  s.update(
                      "INSERT INTO evidence_warm (repository, rule_id, severity, day, finding_count)"
                          + " VALUES (?,?,?,?,?) ON CONFLICT (repository, rule_id, severity, day)"
                          + " DO UPDATE SET finding_count ="
                          + " evidence_warm.finding_count + EXCLUDED.finding_count",
                      key.get(0),
                      key.get(1),
                      key.get(2),
                      java.sql.Date.valueOf((LocalDate) key.get(3)),
                      count));
          return gone.size();
        });
  }

  @Override
  public List<EvidenceSummary> warmSummaries(String repository) {
    return jdbc.query(
        "SELECT repository, rule_id, severity, day, finding_count FROM evidence_warm"
            + " WHERE repository = ? ORDER BY day, rule_id, severity",
        row ->
            new EvidenceSummary(
                row.getString("repository"),
                row.getString("rule_id"),
                row.getString("severity"),
                row.getDate("day").toLocalDate(),
                row.getInt("finding_count")),
        repository);
  }

  private static String write(Map<String, Object> evidence) {
    try {
      return JSON.writeValueAsString(evidence);
    } catch (JsonProcessingException e) {
      throw new StoreException("Evidence could not be serialised", e);
    }
  }

  private static Map<String, Object> read(String json) {
    try {
      return JSON.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (JsonProcessingException e) {
      throw new StoreException("Stored evidence could not be read", e);
    }
  }
}
