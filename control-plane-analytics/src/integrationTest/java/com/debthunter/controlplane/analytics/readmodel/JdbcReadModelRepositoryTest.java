package com.debthunter.controlplane.analytics.readmodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.store.jdbc.StoreException;
import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class JdbcReadModelRepositoryTest extends ReadModelRepositoryContract {

  private DataSource dataSource;

  @Override
  protected ReadModelRepository newRepository() {
    dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return new JdbcReadModelRepository(dataSource);
  }

  @Test
  void aBatchThatFailsPartWayLeavesNoneOfItAppliedNotEvenTheCheckpoint() {
    ProjectionBatch poisoned =
        new ProjectionBatch(
            Map.of("runs", 99L),
            List.of(new RuleDelta(R, S, "rule", LocalDate.of(2026, 1, 2), 5, 0, 0)),
            List.of(snapshot("c1", 1, 4)),
            // PostgreSQL rejects a NUL character in text, so this fails after the writes above.
            List.of(new HotspotPoint(R, S, "c1", "bad\u0000path", day(1), 3.0)),
            List.of(),
            List.of(open("a", 1)),
            List.of());

    assertThatThrownBy(() -> repo.commit(poisoned, day(1))).isInstanceOf(StoreException.class);

    assertThat(repo.checkpoint("runs")).isZero();
    assertThat(repo.lastUpdated()).isEmpty();
    assertThat(repo.snapshots(R, S, day(0), LATER)).isEmpty();
    assertThat(repo.ruleDeltas(R, S, day(0), LATER)).isEmpty();
    assertThat(repo.openFindings(R, S)).isEmpty();
  }

  @Test
  void whatWasCommittedSurvivesANewRepositoryOverTheSameDatabase() {
    repo.commit(
        new ProjectionBatch(
            Map.of("transitions", 12L),
            List.of(),
            List.of(snapshot("c1", 1, 4)),
            List.of(),
            List.of(),
            List.of(),
            List.of()),
        day(3));

    ReadModelRepository restarted = new JdbcReadModelRepository(dataSource);

    assertThat(restarted.checkpoint("transitions")).isEqualTo(12);
    assertThat(restarted.snapshots(R, S, day(0), LATER)).hasSize(1);
    assertThat(restarted.lastUpdated()).contains(day(3));
  }
}
