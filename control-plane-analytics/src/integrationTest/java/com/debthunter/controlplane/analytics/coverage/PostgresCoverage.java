package com.debthunter.controlplane.analytics.coverage;

import com.debthunter.controlplane.analytics.PipelineDriver;
import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.store.registry.JdbcRepositoryRegistry;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import javax.sql.DataSource;

/** The real registry, the real pipeline, and the real coverage store, on PostgreSQL. */
final class PostgresCoverage {

  private PostgresCoverage() {}

  static DataSource database() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return dataSource;
  }

  static CoverageFixture over(DataSource dataSource, int currentToolMajor) {
    return new CoverageFixture(
        new JdbcRepositoryRegistry(dataSource),
        PipelineDriver.postgres(dataSource),
        new JdbcCoverageRepository(dataSource),
        currentToolMajor);
  }

  static CoverageFixture fresh() {
    return over(database(), 0);
  }
}
