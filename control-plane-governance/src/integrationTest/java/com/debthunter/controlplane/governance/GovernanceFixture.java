package com.debthunter.controlplane.governance;

import com.debthunter.controlplane.governance.policy.PolicyBundleValidator;
import com.debthunter.controlplane.governance.simulation.InMemoryPolicyBundleSource;
import com.debthunter.controlplane.governance.simulation.PolicyImpactSimulator;
import com.debthunter.controlplane.governance.simulation.RetainedEvidence;
import com.debthunter.controlplane.governance.simulation.StoreBackedRetainedEvidence;
import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.store.registry.JdbcRepositoryRegistry;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.WorkItemRoute;
import com.debthunter.controlplane.store.suppressions.InMemorySuppressionLookup;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import com.debthunter.controlplane.testkit.RunBundleBuilder;
import com.debthunter.policy.PolicyBundleParser;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import javax.sql.DataSource;
import org.postgresql.ds.PGSimpleDataSource;

/**
 * The real pipeline, the real registry and the real stores on PostgreSQL, with the simulator
 * reading them through a connection that is not allowed to write.
 */
public final class GovernanceFixture {

  public static final Instant DAY0 = Instant.parse("2026-01-01T00:00:00Z");
  public static final Instant TODAY = DAY0.plus(Duration.ofDays(100));

  public final DataSource dataSource;
  public final PipelineDriver pipeline;
  public final JdbcRepositoryRegistry registry;
  public final InMemorySuppressionLookup suppressions = new InMemorySuppressionLookup();
  public final InMemoryPolicyBundleSource bundles = new InMemoryPolicyBundleSource();

  public GovernanceFixture() {
    this.dataSource = PostgresTestDatabase.freshSchema();
    StoreMigrations.migrate(dataSource);
    this.pipeline = PipelineDriver.postgres(dataSource);
    this.registry = new JdbcRepositoryRegistry(dataSource);
  }

  public static Instant day(int n) {
    return DAY0.plus(Duration.ofDays(n));
  }

  public static RunBundleBuilder run(String repository, int day, String runId) {
    return RunBundleBuilder.run()
        .id(runId)
        .repository(repository)
        .commit("c-" + runId)
        .timestamp(day(day).toString());
  }

  public GovernanceFixture onboard(
      String repository, String tier, String stage, String policyVersion) {
    registry.save(
        new OnboardedRepository(
            repository, "team", tier, policyVersion, stage, new WorkItemRoute("ado", "board")));
    return this;
  }

  /** Makes a bundle already in force. */
  public GovernanceFixture currentPolicy(String yaml) {
    bundles.with(new PolicyBundleParser().parse(yaml));
    return this;
  }

  public GovernanceFixture apply(RunBundleBuilder run) {
    pipeline.apply(run);
    return this;
  }

  /** What the simulator reads through: the stores, over a connection that cannot write. */
  public RetainedEvidence evidence() {
    DataSource readOnly = readOnlyView(dataSource);
    return new StoreBackedRetainedEvidence(
        new JdbcRepositoryRegistry(readOnly),
        new com.debthunter.controlplane.store.runs.JdbcRunRepository(readOnly),
        new com.debthunter.controlplane.store.findings.JdbcPseudonymisedFindingStore(readOnly),
        suppressions);
  }

  public PolicyImpactSimulator simulator() {
    return simulatorOver(evidence());
  }

  public PolicyImpactSimulator simulatorOver(RetainedEvidence evidence) {
    return new PolicyImpactSimulator(
        evidence, bundles, new PolicyBundleValidator(), Clock.fixed(TODAY, ZoneOffset.UTC));
  }

  /** The same database, but every session is read-only: a write is an error, not a no-op. */
  static DataSource readOnlyView(DataSource source) {
    PGSimpleDataSource original = (PGSimpleDataSource) source;
    PGSimpleDataSource view = new PGSimpleDataSource();
    view.setUrl(original.getUrl());
    view.setUser(original.getUser());
    view.setPassword(original.getPassword());
    view.setCurrentSchema(original.getCurrentSchema());
    view.setOptions("-c default_transaction_read_only=on");
    return view;
  }

  /**
   * A fingerprint of everything in the database: for every table, how many rows and a hash of their
   * contents. Two equal snapshots mean nothing was inserted, changed or deleted.
   */
  public java.util.Map<String, String> snapshot() {
    java.util.Map<String, String> result = new TreeMap<>();
    try (Connection connection = dataSource.getConnection();
        Statement statement = connection.createStatement()) {
      List<String> tables = new ArrayList<>();
      try (ResultSet rs =
          statement.executeQuery(
              "SELECT table_name FROM information_schema.tables"
                  + " WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'")) {
        while (rs.next()) {
          tables.add(rs.getString(1));
        }
      }
      for (String table : tables) {
        try (ResultSet rs =
            statement.executeQuery(
                "SELECT count(*), md5(coalesce(string_agg(t::text, '|' ORDER BY t::text), ''))"
                    + " FROM \""
                    + table
                    + "\" t")) {
          rs.next();
          result.put(table, rs.getLong(1) + ":" + rs.getString(2));
        }
      }
    } catch (SQLException e) {
      throw new IllegalStateException(e);
    }
    return result;
  }
}
