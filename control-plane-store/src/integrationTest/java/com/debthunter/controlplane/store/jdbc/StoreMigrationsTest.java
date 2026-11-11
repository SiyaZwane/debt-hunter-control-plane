package com.debthunter.controlplane.store.jdbc;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import javax.sql.DataSource;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("integration")
class StoreMigrationsTest {

  @Test
  void aFreshDatabaseIsMigratedAndASecondRunChangesNothing() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();

    assertThat(StoreMigrations.migrate(dataSource)).isPositive();
    assertThat(StoreMigrations.migrate(dataSource)).isZero();
  }

  @Test
  void theSchemaHasNoPlaceToPutAContributorIdentity() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);

    var columns =
        new Jdbc(dataSource)
            .query(
                "SELECT column_name FROM information_schema.columns WHERE table_schema ="
                    + " current_schema()",
                row -> row.getString("column_name").toLowerCase());

    assertThat(columns)
        .noneMatch(
            c ->
                c.contains("author")
                    || c.contains("email")
                    || c.contains("identity") && !c.equals("workload_identity"));
  }

  @Test
  void thePseudonymDirectoryIsNotInThisSchema() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);

    var tables =
        new Jdbc(dataSource)
            .query(
                "SELECT table_name FROM information_schema.tables WHERE table_schema ="
                    + " current_schema()",
                row -> row.getString("table_name"));

    assertThat(tables).noneMatch(t -> t.contains("pseudonym") || t.contains("directory"));
  }
}
