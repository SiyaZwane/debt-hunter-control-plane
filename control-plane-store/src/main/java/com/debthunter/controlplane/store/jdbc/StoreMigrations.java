package com.debthunter.controlplane.store.jdbc;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;

/** Brings a database's schema up to date. Safe to run on every start: applied steps are skipped. */
public final class StoreMigrations {

  private StoreMigrations() {}

  /**
   * Applies any migrations not yet applied.
   *
   * @param dataSource the database
   * @return how many migrations were applied by this call
   */
  public static int migrate(DataSource dataSource) {
    return Flyway.configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .load()
        .migrate()
        .migrationsExecuted;
  }
}
