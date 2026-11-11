package com.debthunter.controlplane.store.policy;

import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;

@Tag("integration")
class JdbcPolicyCatalogueTest extends PolicyCatalogueContract {

  @Override
  protected PolicyCatalogueRepository newRepository() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return new JdbcPolicyCatalogueRepository(dataSource);
  }
}
