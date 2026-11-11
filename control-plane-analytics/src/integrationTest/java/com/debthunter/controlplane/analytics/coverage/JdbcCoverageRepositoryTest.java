package com.debthunter.controlplane.analytics.coverage;

import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;

@Tag("integration")
class JdbcCoverageRepositoryTest extends CoverageRepositoryContract {

  @Override
  protected CoverageRepository newRepository() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return new JdbcCoverageRepository(dataSource);
  }
}
