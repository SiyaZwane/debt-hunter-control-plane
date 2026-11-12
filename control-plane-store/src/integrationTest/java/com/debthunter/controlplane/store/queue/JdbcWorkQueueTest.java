package com.debthunter.controlplane.store.queue;

import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;

@Tag("integration")
class JdbcWorkQueueTest extends WorkQueueContract {

  @Override
  protected WorkQueue newQueue(TestClock clock) {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return new JdbcWorkQueue(dataSource, POLICIES, clock);
  }
}
