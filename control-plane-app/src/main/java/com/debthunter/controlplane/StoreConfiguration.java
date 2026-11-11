package com.debthunter.controlplane;

import com.debthunter.controlplane.store.baselines.BaselineRegistryService;
import com.debthunter.controlplane.store.baselines.JdbcBaselineRegistryRepository;
import com.debthunter.controlplane.store.evidence.EvidenceStorageService;
import com.debthunter.controlplane.store.evidence.JdbcEvidenceRepository;
import com.debthunter.controlplane.store.findings.JdbcPseudonymisedFindingStore;
import com.debthunter.controlplane.store.findings.PseudonymisedFindingStore;
import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.store.lifecycle.FindingStateRepository;
import com.debthunter.controlplane.store.lifecycle.JdbcFindingStateRepository;
import com.debthunter.controlplane.store.lifecycle.JdbcTransitionLogRepository;
import com.debthunter.controlplane.store.lifecycle.TransitionLogRepository;
import com.debthunter.controlplane.store.policy.JdbcPolicyCatalogueRepository;
import com.debthunter.controlplane.store.policy.PolicyCatalogueService;
import com.debthunter.controlplane.store.quarantine.JdbcQuarantineRepository;
import com.debthunter.controlplane.store.quarantine.QuarantineRepository;
import com.debthunter.controlplane.store.registry.JdbcRepositoryRegistry;
import com.debthunter.controlplane.store.registry.RepositoryRegistryService;
import com.debthunter.controlplane.store.runs.JdbcRunRepository;
import com.debthunter.controlplane.store.runs.RunRepository;
import com.debthunter.controlplane.store.suppressions.JdbcSuppressionRepository;
import com.debthunter.controlplane.store.suppressions.SuppressionEventPublisher;
import com.debthunter.controlplane.store.suppressions.SuppressionRegisterService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Supplies the system of record: a pooled, migrated database and the stores over it. The store
 * modules are written against plain interfaces; this decides they are PostgreSQL.
 */
@Configuration(proxyBeanMethods = false)
public class StoreConfiguration {

  private static final Logger log = LoggerFactory.getLogger(StoreConfiguration.class);

  /**
   * The pooled data source, with the schema brought up to date before anything uses it.
   *
   * <p>Fails start-up if the database is not configured, cannot be reached, or cannot be migrated:
   * a control plane with no system of record must not pretend to be running.
   *
   * @param properties the control-plane configuration
   * @return the pool, closed with the application
   */
  @Bean(destroyMethod = "close")
  public HikariDataSource dataSource(ControlPlaneProperties properties) {
    ControlPlaneProperties.Database database = properties.database();
    if (database.url() == null || database.url().isBlank()) {
      throw new IllegalStateException(
          "The system of record is not configured. Set debthunter.controlplane.database.url"
              + " (environment: CONTROL_PLANE_DB_URL, CONTROL_PLANE_DB_USER,"
              + " CONTROL_PLANE_DB_PASSWORD).");
    }
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(database.url());
    config.setUsername(database.username());
    config.setPassword(database.password());
    config.setMaximumPoolSize(database.maxPoolSize());
    config.setPoolName("control-plane");
    HikariDataSource dataSource = new HikariDataSource(config);
    int applied = StoreMigrations.migrate(dataSource);
    log.info("system of record ready, {} migration(s) applied", applied);
    return dataSource;
  }

  /**
   * The repository registry. It is also the {@code RepositoryScopeLookup} the ingestion edge uses,
   * so which workload may publish for which repository is decided by what has been onboarded and
   * granted.
   *
   * @param dataSource the database
   * @return the registry service
   */
  @Bean
  public RepositoryRegistryService repositoryRegistryService(DataSource dataSource) {
    return new RepositoryRegistryService(new JdbcRepositoryRegistry(dataSource));
  }

  /**
   * The run store.
   *
   * @param dataSource the database
   * @return the run repository
   */
  @Bean
  public RunRepository runRepository(DataSource dataSource) {
    return new JdbcRunRepository(dataSource);
  }

  /**
   * Current finding states.
   *
   * @param dataSource the database
   * @return the state repository
   */
  @Bean
  public FindingStateRepository findingStateRepository(DataSource dataSource) {
    return new JdbcFindingStateRepository(dataSource);
  }

  /**
   * The append-only transition log.
   *
   * @param dataSource the database
   * @return the log
   */
  @Bean
  public TransitionLogRepository transitionLogRepository(DataSource dataSource) {
    return new JdbcTransitionLogRepository(dataSource);
  }

  /**
   * Pseudonymised findings.
   *
   * @param dataSource the database
   * @return the finding store
   */
  @Bean
  public PseudonymisedFindingStore pseudonymisedFindingStore(DataSource dataSource) {
    return new JdbcPseudonymisedFindingStore(dataSource);
  }

  /**
   * Bundles set aside because they can never be processed.
   *
   * @param dataSource the database
   * @return the quarantine
   */
  @Bean
  public QuarantineRepository quarantineRepository(DataSource dataSource) {
    return new JdbcQuarantineRepository(dataSource);
  }

  /**
   * Where suppression warnings go. Today they are logged; a notification channel replaces this.
   *
   * @return the publisher
   */
  @Bean
  public SuppressionEventPublisher suppressionEventPublisher() {
    return event ->
        log.warn(
            "suppression nearing expiry suppressionId={} repository={} expiresAt={}",
            event.suppressionId(),
            event.repository(),
            event.expiresAt());
  }

  /**
   * The suppression register. It is also the {@code SuppressionLookup} the lifecycle reads.
   *
   * @param dataSource the database
   * @param events where warnings go
   * @return the register
   */
  @Bean
  public SuppressionRegisterService suppressionRegisterService(
      DataSource dataSource, SuppressionEventPublisher events) {
    return new SuppressionRegisterService(new JdbcSuppressionRepository(dataSource), events);
  }

  /**
   * The baseline pointer registry.
   *
   * @param dataSource the database
   * @return the service
   */
  @Bean
  public BaselineRegistryService baselineRegistryService(DataSource dataSource) {
    return new BaselineRegistryService(new JdbcBaselineRegistryRepository(dataSource));
  }

  /**
   * The policy catalogue.
   *
   * @param dataSource the database
   * @return the service
   */
  @Bean
  public PolicyCatalogueService policyCatalogueService(DataSource dataSource) {
    return new PolicyCatalogueService(new JdbcPolicyCatalogueRepository(dataSource));
  }

  /**
   * Evidence storage with its configured hot-tier lifetime.
   *
   * @param dataSource the database
   * @param properties the control-plane configuration
   * @return the service
   */
  @Bean
  public EvidenceStorageService evidenceStorageService(
      DataSource dataSource, ControlPlaneProperties properties) {
    return new EvidenceStorageService(
        new JdbcEvidenceRepository(dataSource), properties.retention().evidenceHotTtl());
  }
}
