package com.debthunter.controlplane.governance.rollout;

import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.store.rollout.JdbcStageRepository;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import javax.sql.DataSource;

/** A stage manager over a real, migrated PostgreSQL database. */
final class JdbcStageFixture {

  final DataSource dataSource = PostgresTestDatabase.freshSchema();
  final StageFixture stages;

  JdbcStageFixture() {
    StoreMigrations.migrate(dataSource);
    this.stages = new StageFixture(new JdbcStageRepository(dataSource));
  }

  /** A second manager over the same database, as another process or a restart would be. */
  StageManager anotherInstance() {
    return new StageManager(new JdbcStageRepository(dataSource), StageFixture.POLICY, e -> {});
  }
}
