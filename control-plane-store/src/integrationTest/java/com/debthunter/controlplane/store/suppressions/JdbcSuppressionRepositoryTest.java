package com.debthunter.controlplane.store.suppressions;

import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;

@Tag("integration")
class JdbcSuppressionRepositoryTest extends SuppressionRepositoryContract {

  @Override
  protected SuppressionRepository newRepository() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return new JdbcSuppressionRepository(dataSource);
  }
}
