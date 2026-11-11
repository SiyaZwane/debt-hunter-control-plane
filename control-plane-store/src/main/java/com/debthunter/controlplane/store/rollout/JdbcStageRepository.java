package com.debthunter.controlplane.store.rollout;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import com.debthunter.controlplane.store.jdbc.StoreException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import javax.sql.DataSource;

/** {@link StageRepository} over PostgreSQL. */
public final class JdbcStageRepository implements StageRepository {

  private static final ObjectMapper JSON = new ObjectMapper();
  private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() {};
  private static final String COLUMNS =
      "repository, stage, effective_from, owner, exit_evidence, exemption_reason,"
          + " exemption_approved_by, exemption_granted_at, exemption_expires_at, escalated_at";
  private static final String INSERT_ONLY =
      "INSERT INTO rollout_stage_record ("
          + COLUMNS
          + ") VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT (repository) DO NOTHING";
  private static final String UPSERT =
      "INSERT INTO rollout_stage_record ("
          + COLUMNS
          + ") VALUES (?,?,?,?,?,?,?,?,?,?)"
          + " ON CONFLICT (repository) DO UPDATE SET stage = EXCLUDED.stage,"
          + " effective_from = EXCLUDED.effective_from, owner = EXCLUDED.owner,"
          + " exit_evidence = EXCLUDED.exit_evidence,"
          + " exemption_reason = EXCLUDED.exemption_reason,"
          + " exemption_approved_by = EXCLUDED.exemption_approved_by,"
          + " exemption_granted_at = EXCLUDED.exemption_granted_at,"
          + " exemption_expires_at = EXCLUDED.exemption_expires_at,"
          + " escalated_at = EXCLUDED.escalated_at";

  private final Jdbc jdbc;

  /**
   * Creates the repository.
   *
   * @param dataSource the database
   */
  public JdbcStageRepository(DataSource dataSource) {
    this.jdbc = new Jdbc(dataSource);
  }

  @Override
  public Optional<StageRecord> find(String repository) {
    return jdbc.queryOne(
        "SELECT " + COLUMNS + " FROM rollout_stage_record WHERE repository = ?",
        JdbcStageRepository::map,
        repository);
  }

  @Override
  public List<StageRecord> all() {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM rollout_stage_record ORDER BY repository",
        JdbcStageRepository::map);
  }

  @Override
  public Map<String, RolloutStage> currentStages() {
    Map<String, RolloutStage> stages = new TreeMap<>();
    for (StageRecord record : all()) {
      stages.put(record.repository(), record.stage());
    }
    return stages;
  }

  @Override
  public void apply(StageRecord updated, StageEvent event) {
    jdbc.inTransaction(
        session -> {
          Optional<Integer> current =
              session.queryOne(
                  "SELECT stage FROM rollout_stage_record WHERE repository = ? FOR UPDATE",
                  row -> row.getInt("stage"),
                  updated.repository());
          if (event.kind() == StageEvent.Kind.ENROLLED) {
            if (current.isPresent()) {
              throw new StageConflictException("Already in the rollout: " + updated.repository());
            }
          } else if (current.isEmpty() || current.get() != event.fromStage().level()) {
            throw new StageConflictException(
                updated.repository()
                    + " is at "
                    + current.map(RolloutStage::of).map(Enum::toString).orElse("no stage")
                    + ", not "
                    + event.fromStage());
          }
          int written = writeRecord(session, updated, event.kind() == StageEvent.Kind.ENROLLED);
          if (written != 1) {
            // A concurrent enrolment inserted the same repository between our check and our write.
            throw new StageConflictException("Already in the rollout: " + updated.repository());
          }
          session.update(
              "INSERT INTO rollout_stage_event (repository, kind, from_stage, to_stage, at, actor,"
                  + " criteria, reason) VALUES (?,?,?,?,?,?,?,?)",
              event.repository(),
              event.kind().name(),
              event.fromStage() == null ? null : event.fromStage().level(),
              event.toStage().level(),
              event.at(),
              event.actor(),
              toJson(event.criteria()),
              event.reason());
          return null;
        });
  }

  @Override
  public void update(StageRecord updated) {
    jdbc.inTransaction(
        session -> {
          Optional<Integer> current =
              session.queryOne(
                  "SELECT stage FROM rollout_stage_record WHERE repository = ? FOR UPDATE",
                  row -> row.getInt("stage"),
                  updated.repository());
          if (current.isEmpty()) {
            throw new StageConflictException("Not in the rollout: " + updated.repository());
          }
          writeRecord(session, updated, false);
          return null;
        });
  }

  @Override
  public List<StageEvent> events(String repository) {
    return jdbc.query(
        "SELECT repository, kind, from_stage, to_stage, at, actor, criteria, reason"
            + " FROM rollout_stage_event WHERE repository = ? ORDER BY seq",
        JdbcStageRepository::mapEvent,
        repository);
  }

  private static int writeRecord(Jdbc.Session session, StageRecord r, boolean insertOnly) {
    StageExemption x = r.exemption();
    return session.update(
        insertOnly ? INSERT_ONLY : UPSERT,
        r.repository(),
        r.stage().level(),
        r.effectiveFrom(),
        r.owner(),
        toJson(r.exitEvidence()),
        x == null ? null : x.reason(),
        x == null ? null : x.approvedBy(),
        x == null ? null : x.grantedAt(),
        x == null ? null : x.expiresAt(),
        r.escalatedAt());
  }

  private static StageRecord map(ResultSet row) throws SQLException {
    String reason = row.getString("exemption_reason");
    StageExemption exemption =
        reason == null
            ? null
            : new StageExemption(
                reason,
                row.getString("exemption_approved_by"),
                Jdbc.instant(row, "exemption_granted_at"),
                Jdbc.instant(row, "exemption_expires_at"));
    return new StageRecord(
        row.getString("repository"),
        RolloutStage.of(row.getInt("stage")),
        Jdbc.instant(row, "effective_from"),
        row.getString("owner"),
        fromJson(row.getString("exit_evidence")),
        exemption,
        Jdbc.instant(row, "escalated_at"));
  }

  private static StageEvent mapEvent(ResultSet row) throws SQLException {
    String repository = row.getString("repository");
    StageEvent.Kind kind = StageEvent.Kind.valueOf(row.getString("kind"));
    int from = row.getInt("from_stage");
    boolean noFrom = row.wasNull();
    return new StageEvent(
        repository,
        kind,
        noFrom ? null : RolloutStage.of(from),
        RolloutStage.of(row.getInt("to_stage")),
        Jdbc.instant(row, "at"),
        row.getString("actor"),
        fromJson(row.getString("criteria")),
        row.getString("reason"));
  }

  private static String toJson(Map<String, String> map) {
    try {
      return JSON.writeValueAsString(new TreeMap<>(map));
    } catch (JsonProcessingException e) {
      throw new StoreException("Could not encode stage evidence", e);
    }
  }

  private static Map<String, String> fromJson(String json) {
    try {
      return new LinkedHashMap<>(JSON.readValue(json, STRING_MAP));
    } catch (JsonProcessingException e) {
      throw new StoreException("Could not decode stage evidence", e);
    }
  }
}
