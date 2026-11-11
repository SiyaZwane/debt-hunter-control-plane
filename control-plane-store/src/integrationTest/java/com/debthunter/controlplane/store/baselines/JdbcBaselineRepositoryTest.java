package com.debthunter.controlplane.store.baselines;

import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;

@Tag("integration")
class JdbcBaselineRepositoryTest extends BaselineRepositoryContract {

  @Override
  protected BaselineRegistryRepository newRepository() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return new JdbcBaselineRegistryRepository(dataSource);
  }
}
