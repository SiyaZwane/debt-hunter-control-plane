package com.debthunter.controlplane.store.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import com.debthunter.controlplane.store.jdbc.StoreException;
import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import java.time.Instant;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-25: the transition log keeps events in the order they were appended and is genuinely
 * append-only — the database itself refuses to change or remove a row.
 */
@Tag("integration")
class CpAc25_TransitionLogOrderTest {

  private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

  private static FindingTransitionEvent event(
      String id, String run, FindingState from, FindingState to, TransitionCause cause) {
    return new FindingTransitionEvent(
        id, "acme/widgets", "default", "fp-a", from, to, run, AT, cause);
  }

  private static DataSource migrated() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return dataSource;
  }

  @Test
  void cpAc25_historyReadsBackInTheOrderItWasAppendedAcrossSeparateAppendsAndARestart() {
    DataSource dataSource = migrated();
    new JdbcTransitionLogRepository(dataSource)
        .append(List.of(event("e1", "r1", null, FindingState.NEW, TransitionCause.FIRST_OBSERVED)));
    new JdbcTransitionLogRepository(dataSource)
        .append(
            List.of(
                event(
                    "e2",
                    "r2",
                    FindingState.NEW,
                    FindingState.OPEN,
                    TransitionCause.OBSERVED_AGAIN)));
    new JdbcTransitionLogRepository(dataSource)
        .append(
            List.of(
                event(
                    "e3",
                    "r3",
                    FindingState.OPEN,
                    FindingState.RESOLVED,
                    TransitionCause.ABSENT_FROM_COMPLETE_RUN),
                event(
                    "e4",
                    "r4",
                    FindingState.RESOLVED,
                    FindingState.REGRESSED,
                    TransitionCause.REAPPEARED_AFTER_RESOLUTION)));

    List<FindingTransitionEvent> history =
        new JdbcTransitionLogRepository(dataSource).historyOf("acme/widgets", "default", "fp-a");

    assertThat(history)
        .extracting(FindingTransitionEvent::eventId)
        .containsExactly("e1", "e2", "e3", "e4");
    assertThat(history)
        .extracting(FindingTransitionEvent::toState)
        .containsExactly(
            FindingState.NEW, FindingState.OPEN, FindingState.RESOLVED, FindingState.REGRESSED);
  }

  @Test
  void cpAc25_aRetriedBatchDoesNotDuplicateOrReorderWhatAlreadyLanded() {
    DataSource dataSource = migrated();
    JdbcTransitionLogRepository log = new JdbcTransitionLogRepository(dataSource);
    FindingTransitionEvent e1 =
        event("e1", "r1", null, FindingState.NEW, TransitionCause.FIRST_OBSERVED);
    FindingTransitionEvent e2 =
        event("e2", "r2", FindingState.NEW, FindingState.OPEN, TransitionCause.OBSERVED_AGAIN);
    log.append(List.of(e1));

    log.append(List.of(e1, e2));

    assertThat(log.historyOf("acme/widgets", "default", "fp-a")).containsExactly(e1, e2);
  }

  @Test
  void cpAc25_theDatabaseRefusesToUpdateALoggedEvent() {
    DataSource dataSource = migrated();
    new JdbcTransitionLogRepository(dataSource)
        .append(List.of(event("e1", "r1", null, FindingState.NEW, TransitionCause.FIRST_OBSERVED)));

    assertThatThrownBy(
            () -> new Jdbc(dataSource).update("UPDATE transition_log SET to_state = 'RESOLVED'"))
        .isInstanceOf(StoreException.class)
        .satisfies(e -> assertThat(rootMessage(e)).contains("append-only"));
  }

  @Test
  void cpAc25_theDatabaseRefusesToDeleteOrTruncateTheLog() {
    DataSource dataSource = migrated();
    new JdbcTransitionLogRepository(dataSource)
        .append(List.of(event("e1", "r1", null, FindingState.NEW, TransitionCause.FIRST_OBSERVED)));
    Jdbc jdbc = new Jdbc(dataSource);

    assertThatThrownBy(() -> jdbc.update("DELETE FROM transition_log"))
        .isInstanceOf(StoreException.class);
    assertThatThrownBy(() -> jdbc.update("TRUNCATE transition_log"))
        .isInstanceOf(StoreException.class);

    assertThat(new JdbcTransitionLogRepository(dataSource).eventsOfRun("r1")).hasSize(1);
  }

  private static String rootMessage(Throwable e) {
    Throwable root = e;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    return String.valueOf(root.getMessage());
  }
}
