package com.debthunter.controlplane.store.findings;

import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;

@Tag("integration")
class JdbcFindingStoreTest extends PseudonymisedFindingStoreContract {

  @Override
  protected PseudonymisedFindingStore newStore() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return new JdbcPseudonymisedFindingStore(dataSource);
  }
}
