package com.debthunter.controlplane.store.rollout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class JdbcStageRepositoryTest extends StageRepositoryContract {

  private DataSource dataSource;

  @Override
  protected StageRepository newRepository() {
    dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return new JdbcStageRepository(dataSource);
  }

  @Test
  void theEventLogCannotBeEditedOrEmptiedByAnyone() throws SQLException {
    StageRepository repository = newRepository();
    repository.apply(
        new StageRecord("acme/a", RolloutStage.STAGE_1, T0, "team", Map.of(), null, null),
        new StageEvent(
            "acme/a",
            StageEvent.Kind.ENROLLED,
            null,
            RolloutStage.STAGE_1,
            T0,
            "lead",
            Map.of(),
            null));

    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      assertThatThrownBy(
              () -> statement.execute("UPDATE rollout_stage_event SET actor = 'someone'"))
          .hasMessageContaining("append-only");
      assertThatThrownBy(() -> statement.execute("DELETE FROM rollout_stage_event"))
          .hasMessageContaining("append-only");
      assertThatThrownBy(() -> statement.execute("TRUNCATE rollout_stage_event"))
          .hasMessageContaining("append-only");
    }
    assertThat(repository.events("acme/a")).hasSize(1);
  }

  @Test
  void aFailedEventWriteRollsTheRecordChangeBack() throws SQLException {
    StageRepository repository = newRepository();
    repository.apply(
        new StageRecord("acme/a", RolloutStage.STAGE_1, T0, "team", Map.of(), null, null),
        new StageEvent(
            "acme/a",
            StageEvent.Kind.ENROLLED,
            null,
            RolloutStage.STAGE_1,
            T0,
            "lead",
            Map.of(),
            null));
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TABLE rollout_stage_event");
    }

    assertThatThrownBy(
            () ->
                repository.apply(
                    new StageRecord(
                        "acme/a", RolloutStage.STAGE_2, T1, "team", Map.of(), null, null),
                    new StageEvent(
                        "acme/a",
                        StageEvent.Kind.ADVANCED,
                        RolloutStage.STAGE_1,
                        RolloutStage.STAGE_2,
                        T1,
                        "lead",
                        Map.of(),
                        null)))
        .isInstanceOf(RuntimeException.class);
    assertThat(repository.find("acme/a").orElseThrow().stage()).isEqualTo(RolloutStage.STAGE_1);
  }
}
