package com.debthunter.controlplane.store.lifecycle;

import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;

@Tag("integration")
class JdbcFindingStateRepositoryTest extends FindingStateRepositoryContract {

  @Override
  protected FindingStateRepository newRepository() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return new JdbcFindingStateRepository(dataSource);
  }
}
