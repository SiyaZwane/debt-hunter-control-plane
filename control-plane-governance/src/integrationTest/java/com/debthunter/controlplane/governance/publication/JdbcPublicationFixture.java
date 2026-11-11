package com.debthunter.controlplane.governance.publication;

import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.store.policy.JdbcPolicyCatalogueRepository;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import javax.sql.DataSource;

/** A publisher over a real, migrated PostgreSQL catalogue. */
final class JdbcPublicationFixture {

  private JdbcPublicationFixture() {}

  static PublicationFixture create() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return new PublicationFixture(new JdbcPolicyCatalogueRepository(dataSource));
  }
}
