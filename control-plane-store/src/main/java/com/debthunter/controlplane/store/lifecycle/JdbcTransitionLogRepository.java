package com.debthunter.controlplane.store.lifecycle;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;

/**
 * {@link TransitionLogRepository} over PostgreSQL. The class issues only {@code INSERT} and {@code
 * SELECT} against {@code transition_log}; there is no statement anywhere in the application that
 * updates or deletes a row of it. Order is the table's sequence, so history reads back exactly as
 * it was appended, across restarts.
 */
public final class JdbcTransitionLogRepository implements TransitionLogRepository {

  private static final String COLUMNS =
      "event_id, repository, project_slice, fingerprint, from_state, to_state, run_id, event_time,"
          + " cause";

  private final Jdbc jdbc;

  /**
   * Creates the repository.
   *
   * @param dataSource the database
   */
  public JdbcTransitionLogRepository(DataSource dataSource) {
    this.jdbc = new Jdbc(dataSource);
  }

  @Override
  public void append(List<FindingTransitionEvent> events) {
    jdbc.inTransaction(
        s -> {
          for (FindingTransitionEvent e : events) {
            s.update(
                "INSERT INTO transition_log (event_id, repository, project_slice, fingerprint,"
                    + " from_state, to_state, run_id, event_time, cause) VALUES (?,?,?,?,?,?,?,?,?)"
                    + " ON CONFLICT (event_id) DO NOTHING",
                e.eventId(),
                e.repository(),
                e.projectSlice(),
                e.fingerprint(),
                e.fromState() == null ? null : e.fromState().name(),
                e.toState().name(),
                e.runId(),
                e.timestamp(),
                e.cause().name());
          }
          return null;
        });
  }

  @Override
  public List<SequencedTransition> after(long afterSeq, int limit) {
    return jdbc.query(
        "SELECT seq, " + COLUMNS + " FROM transition_log WHERE seq > ? ORDER BY seq LIMIT ?",
        row -> new SequencedTransition(row.getLong("seq"), map(row)),
        afterSeq,
        limit);
  }

  @Override
  public long latestSeq() {
    return jdbc.queryOne(
            "SELECT COALESCE(MAX(seq), 0) AS latest FROM transition_log",
            row -> row.getLong("latest"))
        .orElse(0L);
  }

  @Override
  public List<FindingTransitionEvent> historyOf(
      String repository, String projectSlice, String fingerprint) {
    return jdbc.query(
        "SELECT "
            + COLUMNS
            + " FROM transition_log WHERE repository = ? AND project_slice = ? AND fingerprint = ?"
            + " ORDER BY seq",
        JdbcTransitionLogRepository::map,
        repository,
        projectSlice,
        fingerprint);
  }

  @Override
  public List<FindingTransitionEvent> eventsOfRun(String runId) {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM transition_log WHERE run_id = ? ORDER BY seq",
        JdbcTransitionLogRepository::map,
        runId);
  }

  private static FindingTransitionEvent map(ResultSet row) throws SQLException {
    String from = row.getString("from_state");
    return new FindingTransitionEvent(
        row.getString("event_id"),
        row.getString("repository"),
        row.getString("project_slice"),
        row.getString("fingerprint"),
        from == null ? null : FindingState.valueOf(from),
        FindingState.valueOf(row.getString("to_state")),
        row.getString("run_id"),
        Jdbc.instant(row, "event_time"),
        TransitionCause.valueOf(row.getString("cause")));
  }
}
