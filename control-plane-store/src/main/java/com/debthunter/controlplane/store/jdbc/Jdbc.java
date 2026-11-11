package com.debthunter.controlplane.store.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import javax.sql.DataSource;

/**
 * A deliberately small JDBC helper: parameter binding, row mapping, and transactions, with {@link
 * SQLException} turned into {@link StoreException}.
 *
 * <p>The store modules use plain JDBC rather than a framework so that persistence stays in a
 * non-framework module. Every statement is parameterised; nothing here builds SQL from values.
 */
public final class Jdbc {

  /** Maps the current row of a result set. */
  @FunctionalInterface
  public interface RowMapper<T> {
    /** Reads one row. */
    T map(ResultSet row) throws SQLException;
  }

  /** Statements that run on one connection, inside one transaction when obtained from one. */
  public interface Session {
    /** Executes an insert, update or delete; returns the rows affected. */
    int update(String sql, Object... params);

    /** Runs a query and maps every row. */
    <T> List<T> query(String sql, RowMapper<T> mapper, Object... params);

    /** Runs a query expected to yield at most one row. */
    default <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
      List<T> rows = query(sql, mapper, params);
      return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }
  }

  private final DataSource dataSource;

  /**
   * Creates a helper.
   *
   * @param dataSource where connections come from
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "the data source is a shared service, held by reference intentionally")
  public Jdbc(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  /**
   * Runs work in one transaction: it commits if the work returns and rolls back if it throws.
   *
   * @param work the statements to run
   * @param <T> the result type
   * @return what the work returned
   */
  public <T> T inTransaction(Function<Session, T> work) {
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      try {
        T result = work.apply(new ConnectionSession(connection));
        connection.commit();
        return result;
      } catch (RuntimeException e) {
        connection.rollback();
        throw e;
      }
    } catch (SQLException e) {
      throw new StoreException("The store could not complete the transaction", e);
    }
  }

  /** Runs one update in its own transaction. */
  public int update(String sql, Object... params) {
    return inTransaction(s -> s.update(sql, params));
  }

  /** Runs one query in its own transaction. */
  public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
    return inTransaction(s -> s.query(sql, mapper, params));
  }

  /** Runs one query expected to yield at most one row. */
  public <T> Optional<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
    return inTransaction(s -> s.queryOne(sql, mapper, params));
  }

  /** Reads a nullable timestamp column as an {@link Instant}. */
  public static Instant instant(ResultSet row, String column) throws SQLException {
    Timestamp timestamp = row.getTimestamp(column);
    return timestamp == null ? null : timestamp.toInstant();
  }

  private record ConnectionSession(Connection connection) implements Session {

    @Override
    public int update(String sql, Object... params) {
      try (PreparedStatement statement = prepare(sql, params)) {
        return statement.executeUpdate();
      } catch (SQLException e) {
        throw new StoreException("The store could not run an update", e);
      }
    }

    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... params) {
      try (PreparedStatement statement = prepare(sql, params);
          ResultSet rows = statement.executeQuery()) {
        List<T> result = new ArrayList<>();
        while (rows.next()) {
          result.add(mapper.map(rows));
        }
        return result;
      } catch (SQLException e) {
        throw new StoreException("The store could not run a query", e);
      }
    }

    private PreparedStatement prepare(String sql, Object[] params) throws SQLException {
      PreparedStatement statement = connection.prepareStatement(sql);
      try {
        for (int i = 0; i < params.length; i++) {
          Object value = params[i];
          if (value instanceof Instant instant) {
            statement.setTimestamp(i + 1, Timestamp.from(instant));
          } else {
            statement.setObject(i + 1, value);
          }
        }
        return statement;
      } catch (SQLException | RuntimeException e) {
        statement.close();
        throw e;
      }
    }
  }
}
