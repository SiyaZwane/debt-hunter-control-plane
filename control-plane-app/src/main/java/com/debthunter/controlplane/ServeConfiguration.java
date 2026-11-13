package com.debthunter.controlplane;

import com.debthunter.controlplane.ai.explain.ExplanationService;
import com.debthunter.controlplane.ai.explain.InMemoryExplanationCache;
import com.debthunter.controlplane.ai.explain.RuleBasedExplainer;
import com.debthunter.controlplane.analytics.coverage.CoverageRepository;
import com.debthunter.controlplane.analytics.readmodel.ReadModelRepository;
import com.debthunter.controlplane.analytics.readmodel.Staleness;
import com.debthunter.controlplane.serve.audit.AuditReadService;
import com.debthunter.controlplane.serve.evidence.EvidenceRetrievalService;
import com.debthunter.controlplane.serve.finding.FindingDetailService;
import com.debthunter.controlplane.serve.portfolio.PortfolioDashboardService;
import com.debthunter.controlplane.serve.query.FindingQueryRepository;
import com.debthunter.controlplane.serve.query.JdbcFindingQueryRepository;
import com.debthunter.controlplane.serve.query.QueryService;
import com.debthunter.controlplane.serve.squad.SquadViewService;
import com.debthunter.controlplane.serve.suppression.SquadSuppressionService;
import com.debthunter.controlplane.serve.web.ServeApiConfiguration;
import com.debthunter.controlplane.store.audit.AuditTrail;
import com.debthunter.controlplane.store.evidence.EvidenceAccessLog;
import com.debthunter.controlplane.store.evidence.EvidenceRepository;
import com.debthunter.controlplane.store.evidence.JdbcEvidenceAccessLog;
import com.debthunter.controlplane.store.findings.PseudonymisedFindingStore;
import com.debthunter.controlplane.store.policy.JdbcPolicyCatalogueRepository;
import com.debthunter.controlplane.store.policy.PolicyCatalogueRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistry;
import com.debthunter.controlplane.store.rollout.StageLookup;
import com.debthunter.controlplane.store.runs.RunRepository;
import com.debthunter.controlplane.store.suppressions.SuppressionRegisterService;
import java.time.Clock;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

/**
 * The read APIs: portfolio, squad view, query, evidence retrieval, squad suppressions and the audit
 * trails. Off when {@code zones.serve.enabled} is false.
 *
 * <p>Findings are explained from templates only. No model is configured, so nothing here can reach
 * one.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "debthunter.controlplane.zones.serve",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
@Import(ServeApiConfiguration.class)
class ServeConfiguration {

  private static final int MAX_EXPIRY_DAYS_LIMIT = 365;

  @Bean
  ServeZone serveZone() {
    return new ServeZone();
  }

  @Bean
  ServeGate serveGate(ServeZone zone) {
    return new ServeGate(zone);
  }

  @Bean
  FindingQueryRepository findingQueryRepository(DataSource dataSource) {
    return new JdbcFindingQueryRepository(dataSource);
  }

  @Bean
  EvidenceAccessLog evidenceAccessLog(DataSource dataSource) {
    return new JdbcEvidenceAccessLog(dataSource);
  }

  @Bean
  PolicyCatalogueRepository policyCatalogueRepository(DataSource dataSource) {
    return new JdbcPolicyCatalogueRepository(dataSource);
  }

  @Bean
  ExplanationService explanationService(Clock clock) {
    return new ExplanationService(
        java.util.List.of(new RuleBasedExplainer(clock)), new InMemoryExplanationCache());
  }

  @Bean
  PortfolioDashboardService portfolioDashboardService(
      RepositoryRegistry registry,
      ReadModelRepository readModel,
      CoverageRepository coverage,
      StageLookup stages,
      Staleness staleness,
      Clock clock) {
    return new PortfolioDashboardService(registry, readModel, coverage, stages, staleness, clock);
  }

  @Bean
  SquadViewService squadViewService(
      RepositoryRegistry registry,
      FindingQueryRepository findings,
      CoverageRepository coverage,
      StageLookup stages,
      RunRepository runs,
      PseudonymisedFindingStore findingStore,
      ExplanationService explanations,
      Clock clock) {
    return new SquadViewService(
        registry,
        findings,
        coverage,
        stages,
        new FindingDetailService(runs, findingStore, explanations),
        clock);
  }

  @Bean
  QueryService queryService(
      FindingQueryRepository findings, RepositoryRegistry registry, Clock clock) {
    return new QueryService(findings, registry, clock);
  }

  @Bean
  EvidenceRetrievalService evidenceRetrievalService(
      RunRepository runs,
      PseudonymisedFindingStore findings,
      EvidenceRepository evidence,
      PolicyCatalogueRepository policies,
      RepositoryRegistry registry,
      EvidenceAccessLog log,
      Clock clock) {
    return new EvidenceRetrievalService(runs, findings, evidence, policies, registry, log, clock);
  }

  @Bean
  SquadSuppressionService squadSuppressionService(
      RepositoryRegistry registry,
      SuppressionRegisterService suppressions,
      AuditTrail audit,
      ControlPlaneProperties properties,
      Clock clock) {
    int days = properties.suppressions().maxExpiryDays();
    if (days < 1 || days > MAX_EXPIRY_DAYS_LIMIT) {
      throw new IllegalStateException(
          "debthunter.controlplane.suppressions.max-expiry-days must be from 1 to "
              + MAX_EXPIRY_DAYS_LIMIT
              + ", not "
              + days);
    }
    return new SquadSuppressionService(registry, suppressions, audit, days, clock);
  }

  @Bean
  AuditReadService auditReadService(AuditTrail trail, EvidenceAccessLog evidence, Clock clock) {
    return new AuditReadService(trail, evidence, clock);
  }
}
