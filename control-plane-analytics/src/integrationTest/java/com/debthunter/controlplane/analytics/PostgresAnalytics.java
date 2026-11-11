package com.debthunter.controlplane.analytics;

import com.debthunter.controlplane.analytics.readmodel.JdbcReadModelRepository;
import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import javax.sql.DataSource;

/** The real pipeline, real PostgreSQL stores, and a real PostgreSQL read model. */
final class PostgresAnalytics {

  private PostgresAnalytics() {}

  static DataSource migratedDatabase() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return dataSource;
  }

  static AnalyticsFixture over(DataSource dataSource) {
    return new AnalyticsFixture(
        PipelineDriver.postgres(dataSource), new JdbcReadModelRepository(dataSource));
  }

  static AnalyticsFixture fresh() {
    return over(migratedDatabase());
  }
}
