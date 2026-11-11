package com.debthunter.controlplane.store.registry;

import com.debthunter.controlplane.store.jdbc.Jdbc;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/** {@link RepositoryRegistry} over PostgreSQL. */
public final class JdbcRepositoryRegistry implements RepositoryRegistry {

  private static final String COLUMNS =
      "repository, owner, tier, policy_bundle_version, rollout_stage, route_tracker, route_target";

  private final Jdbc jdbc;

  /**
   * Creates the registry.
   *
   * @param dataSource the database
   */
  public JdbcRepositoryRegistry(DataSource dataSource) {
    this.jdbc = new Jdbc(dataSource);
  }

  @Override
  public void save(OnboardedRepository r) {
    jdbc.update(
        "INSERT INTO onboarded_repository ("
            + COLUMNS
            + ") VALUES (?,?,?,?,?,?,?)"
            + " ON CONFLICT (repository) DO UPDATE SET owner = EXCLUDED.owner,"
            + " tier = EXCLUDED.tier, policy_bundle_version = EXCLUDED.policy_bundle_version,"
            + " rollout_stage = EXCLUDED.rollout_stage, route_tracker = EXCLUDED.route_tracker,"
            + " route_target = EXCLUDED.route_target",
        r.repository(),
        r.owner(),
        r.tier(),
        r.policyBundleVersion(),
        r.rolloutStage(),
        r.route().tracker(),
        r.route().target());
  }

  @Override
  public Optional<OnboardedRepository> find(String repository) {
    return jdbc.queryOne(
        "SELECT " + COLUMNS + " FROM onboarded_repository WHERE repository = ?",
        JdbcRepositoryRegistry::map,
        repository);
  }

  @Override
  public boolean delete(String repository) {
    return jdbc.inTransaction(
        s -> {
          s.update("DELETE FROM workload_scope WHERE repository = ?", repository);
          return s.update("DELETE FROM onboarded_repository WHERE repository = ?", repository) > 0;
        });
  }

  @Override
  public List<OnboardedRepository> all() {
    return jdbc.query(
        "SELECT " + COLUMNS + " FROM onboarded_repository ORDER BY repository",
        JdbcRepositoryRegistry::map);
  }

  @Override
  public void grant(String workloadIdentity, String repository) {
    jdbc.update(
        "INSERT INTO workload_scope (workload_identity, repository) VALUES (?,?)"
            + " ON CONFLICT DO NOTHING",
        workloadIdentity,
        repository);
  }

  @Override
  public void revoke(String workloadIdentity, String repository) {
    jdbc.update(
        "DELETE FROM workload_scope WHERE workload_identity = ? AND repository = ?",
        workloadIdentity,
        repository);
  }

  @Override
  public boolean isGranted(String workloadIdentity, String repository) {
    return jdbc.queryOne(
            "SELECT 1 FROM workload_scope WHERE workload_identity = ? AND repository = ?",
            row -> 1,
            workloadIdentity,
            repository)
        .isPresent();
  }

  @Override
  public Set<String> repositoriesOf(String workloadIdentity) {
    return jdbc
        .query(
            "SELECT repository FROM workload_scope WHERE workload_identity = ?",
            row -> row.getString("repository"),
            workloadIdentity)
        .stream()
        .collect(Collectors.toUnmodifiableSet());
  }

  private static OnboardedRepository map(java.sql.ResultSet row) throws java.sql.SQLException {
    return new OnboardedRepository(
        row.getString("repository"),
        row.getString("owner"),
        row.getString("tier"),
        row.getString("policy_bundle_version"),
        row.getString("rollout_stage"),
        new WorkItemRoute(row.getString("route_tracker"), row.getString("route_target")));
  }
}
