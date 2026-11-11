package com.debthunter.controlplane.testkit;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * A single real PostgreSQL server, started lazily in a container and shared by every test in the
 * JVM. Each caller gets a data source on its own freshly created schema, so tests never see each
 * other's rows and never need to clean up. Requires a running Docker daemon.
 */
public final class PostgresTestDatabase {

  private static final DockerImageName IMAGE = DockerImageName.parse("postgres:16-alpine");

  private static PostgreSQLContainer<?> container;

  private PostgresTestDatabase() {}

  /** How to connect to a test schema, for configuring an application under test. */
  public record Connection(String jdbcUrl, String username, String password) {}

  /**
   * Connection details for a new, empty schema of the shared server.
   *
   * @return the URL (already defaulting to that schema), user, and password
   */
  public static synchronized Connection freshSchemaConnection() {
    DataSource dataSource = freshSchema();
    PGSimpleDataSource pg = (PGSimpleDataSource) dataSource;
    // getUrl() omits the schema, so put it back: the application must land in the same schema.
    String separator = pg.getUrl().contains("?") ? "&" : "?";
    return new Connection(
        pg.getUrl() + separator + "currentSchema=" + pg.getCurrentSchema(),
        pg.getUser(),
        pg.getPassword());
  }

  /**
   * A data source on a new, empty schema of the shared server.
   *
   * @return a data source whose connections default to that schema
   */
  public static synchronized DataSource freshSchema() {
    if (container == null) {
      PostgreSQLContainer<?> started = new PostgreSQLContainer<>(IMAGE);
      started.start();
      Runtime.getRuntime().addShutdownHook(new Thread(started::stop, "stop-postgres-test-db"));
      container = started;
    }
    String schema = "t_" + UUID.randomUUID().toString().replace("-", "");
    try (java.sql.Connection connection = open(container, null);
        Statement statement = connection.createStatement()) {
      statement.execute("CREATE SCHEMA " + schema);
    } catch (SQLException e) {
      throw new IllegalStateException("Could not create a test schema", e);
    }
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(container.getJdbcUrl());
    dataSource.setUser(container.getUsername());
    dataSource.setPassword(container.getPassword());
    dataSource.setCurrentSchema(schema);
    return dataSource;
  }

  private static java.sql.Connection open(PostgreSQLContainer<?> db, String schema)
      throws SQLException {
    PGSimpleDataSource dataSource = new PGSimpleDataSource();
    dataSource.setUrl(db.getJdbcUrl());
    dataSource.setUser(db.getUsername());
    dataSource.setPassword(db.getPassword());
    if (schema != null) {
      dataSource.setCurrentSchema(schema);
    }
    return dataSource.getConnection();
  }
}
