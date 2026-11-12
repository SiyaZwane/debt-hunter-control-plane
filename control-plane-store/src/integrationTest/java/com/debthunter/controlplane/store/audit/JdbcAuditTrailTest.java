package com.debthunter.controlplane.store.audit;

import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;

@Tag("integration")
class JdbcAuditTrailTest extends AuditTrailContract {

  @Override
  protected AuditTrail newTrail() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    return new JdbcAuditTrail(dataSource);
  }
}
