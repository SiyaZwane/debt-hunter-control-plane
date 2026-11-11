package com.debthunter.controlplane.analytics.coverage;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import com.debthunter.controlplane.store.jdbc.StoreException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;

/** {@link CoverageRepository} over PostgreSQL. A new report replaces the old in one transaction. */
public final class JdbcCoverageRepository implements CoverageRepository {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final Jdbc jdbc;

  /**
   * Creates the repository.
   *
   * @param dataSource the database
   */
  public JdbcCoverageRepository(DataSource dataSource) {
    this.jdbc = new Jdbc(dataSource);
  }

  @Override
  public void replace(CoverageReport report) {
    jdbc.inTransaction(
        s -> {
          s.update("DELETE FROM rm_coverage");
          for (RepositoryCoverage c : report.repositories()) {
            s.update(
                "INSERT INTO rm_coverage (repository, state, reasons, last_run_received_at,"
                    + " last_tool_version, onboarded, in_estate) VALUES (?,?,?,?,?,?,?)",
                c.repository(),
                c.state().name(),
                write(c.reasons()),
                c.lastRunReceivedAt(),
                c.lastToolVersion(),
                c.onboarded(),
                c.inEstate());
          }
          s.update(
              "INSERT INTO rm_coverage_report (id, reconciled_at, estate_available, warnings)"
                  + " VALUES (1,?,?,?) ON CONFLICT (id) DO UPDATE SET"
                  + " reconciled_at = EXCLUDED.reconciled_at,"
                  + " estate_available = EXCLUDED.estate_available,"
                  + " warnings = EXCLUDED.warnings",
              report.reconciledAt(),
              report.estateAvailable(),
              write(report.warnings()));
          return null;
        });
  }

  @Override
  public Optional<CoverageReport> latest() {
    return jdbc.inTransaction(
        s -> {
          Optional<Object[]> header =
              s.queryOne(
                  "SELECT reconciled_at, estate_available, warnings FROM rm_coverage_report"
                      + " WHERE id = 1",
                  row ->
                      new Object[] {
                        Jdbc.instant(row, "reconciled_at"),
                        row.getBoolean("estate_available"),
                        row.getString("warnings")
                      });
          if (header.isEmpty()) {
            return Optional.<CoverageReport>empty();
          }
          List<RepositoryCoverage> repositories =
              s.query(
                  "SELECT repository, state, reasons, last_run_received_at, last_tool_version,"
                      + " onboarded, in_estate FROM rm_coverage ORDER BY lower(repository),"
                      + " repository",
                  row ->
                      new RepositoryCoverage(
                          row.getString("repository"),
                          CoverageState.valueOf(row.getString("state")),
                          readList(row.getString("reasons")),
                          Jdbc.instant(row, "last_run_received_at"),
                          row.getString("last_tool_version"),
                          row.getBoolean("onboarded"),
                          (Boolean) row.getObject("in_estate")));
          Object[] h = header.get();
          return Optional.of(
              new CoverageReport(
                  (java.time.Instant) h[0], (Boolean) h[1], repositories, readList((String) h[2])));
        });
  }

  private static String write(List<String> values) {
    try {
      return JSON.writeValueAsString(values);
    } catch (JsonProcessingException e) {
      throw new StoreException("Could not serialise coverage details", e);
    }
  }

  private static List<String> readList(String json) {
    try {
      return JSON.readValue(json, new TypeReference<List<String>>() {});
    } catch (JsonProcessingException e) {
      throw new StoreException("Stored coverage details could not be read", e);
    }
  }
}
