package com.debthunter.controlplane.store.registry;

import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;

@Tag("integration")
class JdbcRepositoryRegistryTest extends RepositoryRegistryContract {

  @Override
  protected RepositoryRegistry newRegistry() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return new JdbcRepositoryRegistry(dataSource);
  }
}
