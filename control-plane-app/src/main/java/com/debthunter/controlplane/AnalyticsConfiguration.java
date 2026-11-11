package com.debthunter.controlplane;

import com.debthunter.controlplane.analytics.coverage.CoverageReport;
import com.debthunter.controlplane.analytics.coverage.CoverageRepository;
import com.debthunter.controlplane.analytics.coverage.CoverageSettings;
import com.debthunter.controlplane.analytics.coverage.CoverageTracker;
import com.debthunter.controlplane.analytics.coverage.JdbcCoverageRepository;
import com.debthunter.controlplane.analytics.coverage.estate.AzureReposEstateProvider;
import com.debthunter.controlplane.analytics.coverage.estate.GitHubEstateProvider;
import com.debthunter.controlplane.analytics.coverage.estate.SourceControlEstateProvider;
import com.debthunter.controlplane.analytics.drift.ArchitectureDriftTracker;
import com.debthunter.controlplane.analytics.projection.MetricProjector;
import com.debthunter.controlplane.analytics.readmodel.JdbcReadModelRepository;
import com.debthunter.controlplane.analytics.readmodel.ReadModelRepository;
import com.debthunter.controlplane.analytics.readmodel.Staleness;
import com.debthunter.controlplane.analytics.remediation.RemediationEffectivenessCalculator;
import com.debthunter.controlplane.analytics.trend.TrendEngine;
import com.debthunter.controlplane.store.findings.PseudonymisedFindingStore;
import com.debthunter.controlplane.store.lifecycle.TransitionLogRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistryService;
import com.debthunter.controlplane.store.runs.RunRepository;
import java.net.URI;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles analytics: the read models, the asynchronous projector that keeps them current, and the
 * engines that query them.
 *
 * <p>Nothing on the ingest path refers to any of this. The projector runs on its own thread, only
 * reads the live stores, and a failure or stall in it leaves ingest untouched.
 */
@Configuration(proxyBeanMethods = false)
public class AnalyticsConfiguration {

  /**
   * The materialised read models.
   *
   * @param dataSource the database
   * @return the read-model repository
   */
  @Bean
  @ConditionalOnMissingBean(ReadModelRepository.class)
  public ReadModelRepository readModelRepository(DataSource dataSource) {
    return new JdbcReadModelRepository(dataSource);
  }

  /**
   * The projector that follows the system of record.
   *
   * @param transitions the transition log (read-only use)
   * @param runs the run store (read-only use)
   * @param findings the finding store (read-only use)
   * @param readModel where aggregates are written
   * @param clock the clock
   * @param properties the control-plane configuration
   * @return the projector
   */
  @Bean
  public MetricProjector metricProjector(
      TransitionLogRepository transitions,
      RunRepository runs,
      PseudonymisedFindingStore findings,
      ReadModelRepository readModel,
      Clock clock,
      ControlPlaneProperties properties) {
    return new MetricProjector(
        transitions, runs, findings, readModel, clock, properties.analytics().batchSize());
  }

  /**
   * Runs the projector on a schedule, off the request and ingest threads.
   *
   * @param projector the projector
   * @param properties the control-plane configuration
   * @return the lifecycle component
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "debthunter.controlplane.analytics",
      name = "projector-enabled",
      havingValue = "true",
      matchIfMissing = true)
  ProjectorLifecycle projectorLifecycle(
      MetricProjector projector, ControlPlaneProperties properties) {
    return new ProjectorLifecycle(projector, properties.analytics().projectionInterval());
  }

  /**
   * Decides whether query results are stale.
   *
   * @param readModel the read models
   * @param clock the clock
   * @param properties the control-plane configuration
   * @return the staleness check
   */
  @Bean
  public Staleness staleness(
      ReadModelRepository readModel, Clock clock, ControlPlaneProperties properties) {
    return new Staleness(readModel, clock, properties.analytics().staleAfter());
  }

  /**
   * Trend queries.
   *
   * @param readModel the read models
   * @param staleness the staleness check
   * @return the engine
   */
  @Bean
  public TrendEngine trendEngine(ReadModelRepository readModel, Staleness staleness) {
    return new TrendEngine(readModel, staleness);
  }

  /**
   * Architecture drift queries.
   *
   * @param readModel the read models
   * @param staleness the staleness check
   * @return the tracker
   */
  @Bean
  public ArchitectureDriftTracker architectureDriftTracker(
      ReadModelRepository readModel, Staleness staleness) {
    return new ArchitectureDriftTracker(readModel, staleness);
  }

  /**
   * Remediation-effectiveness queries.
   *
   * @param readModel the read models
   * @param staleness the staleness check
   * @return the calculator
   */
  @Bean
  public RemediationEffectivenessCalculator remediationEffectivenessCalculator(
      ReadModelRepository readModel, Staleness staleness) {
    return new RemediationEffectivenessCalculator(readModel, staleness);
  }

  // ---- coverage and adoption -------------------------------------------------------------------

  /**
   * Lists a GitHub organisation's repositories, when configured.
   *
   * @param properties the control-plane configuration
   * @return the provider
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "debthunter.controlplane.analytics.coverage.estate",
      name = "provider",
      havingValue = "github")
  public SourceControlEstateProvider gitHubEstateProvider(ControlPlaneProperties properties) {
    ControlPlaneProperties.Estate estate = properties.analytics().coverage().estate();
    return new GitHubEstateProvider(
        URI.create(blankTo(estate.apiUrl(), "https://api.github.com")),
        estate.organization(),
        estate.token(),
        estate.timeout());
  }

  /**
   * Lists an Azure DevOps organisation's repositories, when configured.
   *
   * @param properties the control-plane configuration
   * @return the provider
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "debthunter.controlplane.analytics.coverage.estate",
      name = "provider",
      havingValue = "azure")
  public SourceControlEstateProvider azureReposEstateProvider(ControlPlaneProperties properties) {
    ControlPlaneProperties.Estate estate = properties.analytics().coverage().estate();
    return new AzureReposEstateProvider(
        URI.create(blankTo(estate.apiUrl(), "https://dev.azure.com")),
        estate.organization(),
        estate.token(),
        estate.timeout());
  }

  /**
   * Where the latest coverage report is kept.
   *
   * @param dataSource the database
   * @return the repository
   */
  @Bean
  public CoverageRepository coverageRepository(DataSource dataSource) {
    return new JdbcCoverageRepository(dataSource);
  }

  /**
   * Reconciles the registry against the source-control estate.
   *
   * @param registry the repository registry
   * @param runs the run store (read-only use)
   * @param estate the estate provider, if one is configured
   * @param coverage where reports are kept
   * @param clock the clock
   * @param properties the control-plane configuration
   * @return the tracker
   */
  @Bean
  public CoverageTracker coverageTracker(
      RepositoryRegistryService registry,
      RunRepository runs,
      ObjectProvider<SourceControlEstateProvider> estate,
      CoverageRepository coverage,
      Clock clock,
      ControlPlaneProperties properties) {
    ControlPlaneProperties.Coverage c = properties.analytics().coverage();
    return new CoverageTracker(
        registry,
        runs,
        estate.getIfAvailable(),
        coverage,
        new CoverageSettings(c.cadence(), c.currentToolMajor(), c.degradedWindow()),
        clock,
        new Staleness(
            () -> coverage.latest().map(CoverageReport::reconciledAt), clock, c.staleAfter()));
  }

  /**
   * Runs the coverage reconciliation on a schedule (nightly by default).
   *
   * @param tracker the tracker
   * @param properties the control-plane configuration
   * @return the lifecycle component
   */
  @Bean
  @ConditionalOnProperty(
      prefix = "debthunter.controlplane.analytics.coverage",
      name = "enabled",
      havingValue = "true",
      matchIfMissing = true)
  CoverageReconciliationLifecycle coverageReconciliationLifecycle(
      CoverageTracker tracker, ControlPlaneProperties properties) {
    ControlPlaneProperties.Coverage c = properties.analytics().coverage();
    return new CoverageReconciliationLifecycle(tracker, c.initialDelay(), c.reconcileInterval());
  }

  private static String blankTo(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }
}
