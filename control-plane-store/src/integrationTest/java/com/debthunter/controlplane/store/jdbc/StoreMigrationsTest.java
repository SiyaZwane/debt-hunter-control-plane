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
  void apartFromThePseudonymDirectoryTheSchemaHasNoPlaceToPutAContributorIdentity() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);

    var columns =
        new Jdbc(dataSource)
            .query(
                "SELECT column_name FROM information_schema.columns WHERE table_schema ="
                    + " current_schema() AND table_name <> 'pseudonym_directory'",
                row -> row.getString("column_name").toLowerCase());

    assertThat(columns)
        .noneMatch(
            c ->
                c.contains("author")
                    || c.contains("email")
                    || c.contains("identity") && !c.equals("workload_identity"));
  }

  @Test
  void theOnlyTableNamedForPseudonymsIsTheDirectoryAndItHoldsIdentitiesOnlyAsSealedBytes() {
    DataSource dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    Jdbc jdbc = new Jdbc(dataSource);

    var tables =
        jdbc.query(
            "SELECT table_name FROM information_schema.tables WHERE table_schema ="
                + " current_schema()",
            row -> row.getString("table_name"));
    var identityColumns =
        jdbc.query(
            "SELECT column_name || ':' || data_type AS c FROM information_schema.columns WHERE"
                + " table_schema = current_schema() AND table_name = 'pseudonym_directory'"
                + " AND (column_name LIKE '%identity%' OR column_name LIKE '%email%')",
            row -> row.getString("c"));

    assertThat(tables)
        .filteredOn(t -> t.contains("pseudonym") || t.contains("directory"))
        .containsExactly("pseudonym_directory");
    assertThat(identityColumns).containsExactly("sealed_identity:bytea");
  }
}
