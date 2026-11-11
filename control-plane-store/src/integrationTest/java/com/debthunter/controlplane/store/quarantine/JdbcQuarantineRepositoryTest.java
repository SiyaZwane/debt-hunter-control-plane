package com.debthunter.controlplane.store.quarantine;

import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;

@Tag("integration")
class JdbcQuarantineRepositoryTest extends QuarantineRepositoryContract {

  @Override
  protected QuarantineRepository newRepository() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return new JdbcQuarantineRepository(dataSource);
  }
}
