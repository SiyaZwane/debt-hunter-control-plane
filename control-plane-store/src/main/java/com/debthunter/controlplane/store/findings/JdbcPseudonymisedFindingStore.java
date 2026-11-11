package com.debthunter.controlplane.store.findings;

import com.debthunter.controlplane.store.identity.Pseudonym;
import com.debthunter.controlplane.store.jdbc.Jdbc;
import com.debthunter.controlplane.store.jdbc.StoreException;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;

/**
 * {@link PseudonymisedFindingStore} over PostgreSQL. It accepts only {@link PseudonymisedFinding},
 * so a raw contributor identity cannot be written by construction.
 */
public final class JdbcPseudonymisedFindingStore implements PseudonymisedFindingStore {

  private static final ObjectMapper JSON = new ObjectMapper();

  private final Jdbc jdbc;

  /**
   * Creates the store.
   *
   * @param dataSource the database
   */
  public JdbcPseudonymisedFindingStore(DataSource dataSource) {
    this.jdbc = new Jdbc(dataSource);
  }

  @Override
  public void saveRun(String runId, String repository, List<PseudonymisedFinding> findings) {
    jdbc.inTransaction(
        s -> {
          s.update("DELETE FROM finding WHERE run_id = ?", runId);
          int ordinal = 0;
          for (PseudonymisedFinding f : findings) {
            s.update(
                "INSERT INTO finding (run_id, ordinal, repository, finding_id, rule_id, category,"
                    + " severity, confidence, path, start_line, message, evidence, score, is_new,"
                    + " fingerprint, contributors) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                runId,
                ordinal++,
                repository,
                f.id(),
                f.ruleId(),
                f.category().name(),
                f.severity().name(),
                f.confidence(),
                f.path(),
                f.startLine(),
                f.message(),
                write(f.evidence()),
                f.score(),
                f.isNew(),
                f.fingerprint(),
                String.join(",", f.contributors().stream().map(Pseudonym::value).toList()));
          }
          return null;
        });
  }

  @Override
  public List<PseudonymisedFinding> findingsForRun(String runId) {
    return jdbc.query(
        "SELECT finding_id, rule_id, category, severity, confidence, path, start_line, message,"
            + " evidence, score, is_new, fingerprint, contributors FROM finding WHERE run_id = ?"
            + " ORDER BY ordinal",
        row -> {
          String contributors = row.getString("contributors");
          return new PseudonymisedFinding(
              row.getString("finding_id"),
              row.getString("rule_id"),
              Category.valueOf(row.getString("category")),
              Severity.valueOf(row.getString("severity")),
              row.getDouble("confidence"),
              row.getString("path"),
              row.getInt("start_line"),
              row.getString("message"),
              read(row.getString("evidence")),
              row.getDouble("score"),
              row.getBoolean("is_new"),
              row.getString("fingerprint"),
              contributors.isEmpty()
                  ? List.of()
                  : Arrays.stream(contributors.split(",")).map(Pseudonym::fromStored).toList());
        },
        runId);
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
