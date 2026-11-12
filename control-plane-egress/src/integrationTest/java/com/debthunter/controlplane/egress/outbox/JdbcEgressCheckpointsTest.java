package com.debthunter.controlplane.egress.outbox;

import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;

@Tag("integration")
class JdbcEgressCheckpointsTest extends EgressCheckpointsContract {

  @Override
  protected EgressCheckpoints newCheckpoints() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return new JdbcEgressCheckpoints(dataSource);
  }
}
