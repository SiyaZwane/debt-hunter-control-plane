package com.debthunter.controlplane.egress.workitem;

import com.debthunter.controlplane.egress.MutableClock;
import com.debthunter.controlplane.egress.outbox.JdbcEgressCheckpoints;
import com.debthunter.controlplane.store.findings.JdbcPseudonymisedFindingStore;
import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.store.lifecycle.JdbcTransitionLogRepository;
import com.debthunter.controlplane.store.queue.JdbcWorkQueue;
import com.debthunter.controlplane.store.registry.JdbcRepositoryRegistry;
import com.debthunter.controlplane.store.suppressions.JdbcSuppressionRepository;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import javax.sql.DataSource;

/** Stores that live in a fresh PostgreSQL schema with every migration applied. */
final class JdbcStores {

  private JdbcStores() {}

  static DataSource freshDatabase() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return dataSource;
  }

  static WorkItemHarness.Stores create(DataSource dataSource, MutableClock clock) {
    return new WorkItemHarness.Stores(
        new JdbcTransitionLogRepository(dataSource),
        new JdbcPseudonymisedFindingStore(dataSource),
        new JdbcSuppressionRepository(dataSource),
        new JdbcRepositoryRegistry(dataSource),
        new JdbcWorkItemLinkRepository(dataSource),
        new JdbcEgressCheckpoints(dataSource),
        new JdbcWorkQueue(dataSource, InMemoryStores.POLICIES, clock));
  }

  static WorkItemHarness harness() {
    MutableClock clock = new MutableClock();
    return new WorkItemHarness(create(freshDatabase(), clock), clock);
  }
}
