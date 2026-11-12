package com.debthunter.controlplane.egress.digest;

import com.debthunter.controlplane.egress.MutableClock;
import com.debthunter.controlplane.store.findings.JdbcPseudonymisedFindingStore;
import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.store.lifecycle.JdbcTransitionLogRepository;
import com.debthunter.controlplane.store.queue.JdbcWorkQueue;
import com.debthunter.controlplane.store.registry.JdbcRepositoryRegistry;
import com.debthunter.controlplane.store.suppressions.JdbcSuppressionRepository;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import javax.sql.DataSource;

/** Stores that live in a fresh PostgreSQL schema with every migration applied. */
final class JdbcDigestParts {

  private JdbcDigestParts() {}

  static DataSource freshDatabase() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return dataSource;
  }

  static DigestHarness.Parts create(DataSource dataSource, MutableClock clock) {
    return new DigestHarness.Parts(
        new JdbcTransitionLogRepository(dataSource),
        new JdbcPseudonymisedFindingStore(dataSource),
        new JdbcSuppressionRepository(dataSource),
        new JdbcRepositoryRegistry(dataSource),
        new JdbcDigestLedger(dataSource),
        new JdbcSquadChannelRepository(dataSource),
        new JdbcOpenFindingCounts(dataSource),
        new JdbcWorkQueue(dataSource, InMemoryDigestParts.POLICIES, clock));
  }

  static DigestHarness harness() {
    MutableClock clock = new MutableClock();
    return new DigestHarness(create(freshDatabase(), clock), clock);
  }
}
