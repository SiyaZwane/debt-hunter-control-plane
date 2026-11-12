package com.debthunter.controlplane.store.evidence;

import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;

@Tag("integration")
class JdbcEvidenceAccessLogTest extends EvidenceAccessLogContract {

  @Override
  protected EvidenceAccessLog newLog() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return new JdbcEvidenceAccessLog(dataSource);
  }
}
