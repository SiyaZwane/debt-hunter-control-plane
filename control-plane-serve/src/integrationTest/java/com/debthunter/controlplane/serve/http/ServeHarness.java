package com.debthunter.controlplane.serve.http;

import com.debthunter.controlplane.analytics.coverage.CoverageRepository;
import com.debthunter.controlplane.analytics.coverage.InMemoryCoverageRepository;
import com.debthunter.controlplane.analytics.readmodel.JdbcReadModelRepository;
import com.debthunter.controlplane.analytics.readmodel.ProjectionBatch;
import com.debthunter.controlplane.analytics.readmodel.RunSnapshot;
import com.debthunter.controlplane.analytics.readmodel.Staleness;
import com.debthunter.controlplane.api.security.ControlPlaneJwtDecoders;
import com.debthunter.controlplane.api.security.IngestionSecurityConfiguration;
import com.debthunter.controlplane.serve.access.Roles;
import com.debthunter.controlplane.serve.audit.AuditReadService;
import com.debthunter.controlplane.serve.evidence.EvidenceRetrievalService;
import com.debthunter.controlplane.serve.finding.FindingDetailService;
import com.debthunter.controlplane.serve.finding.FindingFixture;
import com.debthunter.controlplane.serve.portfolio.PortfolioDashboardService;
import com.debthunter.controlplane.serve.query.JdbcFindingQueryRepository;
import com.debthunter.controlplane.serve.query.QueryService;
import com.debthunter.controlplane.serve.squad.SquadViewService;
import com.debthunter.controlplane.serve.suppression.SquadSuppressionService;
import com.debthunter.controlplane.serve.web.ServeApiConfiguration;
import com.debthunter.controlplane.store.audit.AuditTrail;
import com.debthunter.controlplane.store.audit.JdbcAuditTrail;
import com.debthunter.controlplane.store.evidence.EvidenceAccessLog;
import com.debthunter.controlplane.store.evidence.EvidenceSnapshot;
import com.debthunter.controlplane.store.evidence.JdbcEvidenceAccessLog;
import com.debthunter.controlplane.store.evidence.JdbcEvidenceRepository;
import com.debthunter.controlplane.store.findings.JdbcPseudonymisedFindingStore;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.identity.Pseudonym;
import com.debthunter.controlplane.store.jdbc.StoreMigrations;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.lifecycle.FindingStateRecord;
import com.debthunter.controlplane.store.lifecycle.JdbcFindingStateRepository;
import com.debthunter.controlplane.store.policy.JdbcPolicyCatalogueRepository;
import com.debthunter.controlplane.store.policy.PolicyBundleRecord;
import com.debthunter.controlplane.store.registry.JdbcRepositoryRegistry;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.WorkItemRoute;
import com.debthunter.controlplane.store.rollout.RolloutStage;
import com.debthunter.controlplane.store.runs.EngineRecord;
import com.debthunter.controlplane.store.runs.JdbcRunRepository;
import com.debthunter.controlplane.store.runs.RunRecord;
import com.debthunter.controlplane.store.runs.RunStatus;
import com.debthunter.controlplane.store.suppressions.JdbcSuppressionRepository;
import com.debthunter.controlplane.store.suppressions.SuppressionRegisterService;
import com.debthunter.controlplane.testkit.PostgresTestDatabase;
import com.debthunter.controlplane.testkit.TestJwtIssuer;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import javax.sql.DataSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;

/**
 * The read APIs in a real Spring context: the real security chain, the production token decoder,
 * the real controllers, and real PostgreSQL behind them, with helpers to put the estate in a known
 * state.
 */
final class ServeHarness implements AutoCloseable {

  static final Instant NOW = Instant.parse("2026-07-01T12:00:00Z");
  static final String WIDGETS = "acme/widgets";
  static final String GADGETS = "acme/gadgets";
  static final String THING = "other/thing";
  static final String CONTRIBUTOR = "c".repeat(64);
  static final String PURPOSE = "investigating a regression in widgets";
  static final int MAX_SUPPRESSION_DAYS = 90;

  final TestJwtIssuer issuer = new TestJwtIssuer();
  final DataSource dataSource = PostgresTestDatabase.freshSchema();
  final Clock clock;
  final Map<String, RolloutStage> stages = new HashMap<>();
  final JdbcRepositoryRegistry registry;
  final JdbcRunRepository runs;
  final JdbcPseudonymisedFindingStore findings;
  final JdbcFindingStateRepository states;
  final JdbcEvidenceRepository evidence;
  final JdbcPolicyCatalogueRepository policies;
  final JdbcEvidenceAccessLog accessLog;
  final JdbcReadModelRepository readModel;
  final JdbcAuditTrail auditTrail;
  final JdbcSuppressionRepository suppressionRepository;
  final InMemoryCoverageRepository coverage = new InMemoryCoverageRepository();
  final WebTestClient client;

  private final AnnotationConfigApplicationContext context =
      new AnnotationConfigApplicationContext();

  ServeHarness() {
    this(NOW, Function.identity());
  }

  ServeHarness(Instant now, Function<EvidenceAccessLog, EvidenceAccessLog> wrapLog) {
    this(now, wrapLog, Function.identity());
  }

  ServeHarness(
      Instant now,
      Function<EvidenceAccessLog, EvidenceAccessLog> wrapLog,
      Function<AuditTrail, AuditTrail> wrapAudit) {
    StoreMigrations.migrate(dataSource);
    clock = Clock.fixed(now, ZoneOffset.UTC);
    registry = new JdbcRepositoryRegistry(dataSource);
    runs = new JdbcRunRepository(dataSource);
    findings = new JdbcPseudonymisedFindingStore(dataSource);
    states = new JdbcFindingStateRepository(dataSource);
    evidence = new JdbcEvidenceRepository(dataSource);
    policies = new JdbcPolicyCatalogueRepository(dataSource);
    accessLog = new JdbcEvidenceAccessLog(dataSource);
    readModel = new JdbcReadModelRepository(dataSource);
    auditTrail = new JdbcAuditTrail(dataSource);
    suppressionRepository = new JdbcSuppressionRepository(dataSource);
    JdbcFindingQueryRepository queries = new JdbcFindingQueryRepository(dataSource);

    onboard(WIDGETS, "team-a");
    onboard(GADGETS, "team-a");
    onboard(THING, "team-b");
    stages.put(WIDGETS, RolloutStage.STAGE_3);
    stages.put(GADGETS, RolloutStage.STAGE_2);
    stages.put(THING, RolloutStage.STAGE_1);

    PortfolioDashboardService portfolio =
        new PortfolioDashboardService(
            registry,
            readModel,
            coverage,
            () -> stages,
            new Staleness(readModel, clock, Duration.ofHours(1)),
            clock);
    SquadViewService squad =
        new SquadViewService(
            registry,
            queries,
            coverage,
            () -> stages,
            new FindingDetailService(runs, findings, FindingFixture.templatesOnly()),
            clock);
    QueryService query = new QueryService(queries, registry, clock);
    EvidenceRetrievalService evidenceService =
        new EvidenceRetrievalService(
            runs, findings, evidence, policies, registry, wrapLog.apply(accessLog), clock);

    SquadSuppressionService suppressionService =
        new SquadSuppressionService(
            registry,
            new SuppressionRegisterService(suppressionRepository, event -> {}),
            wrapAudit.apply(auditTrail),
            MAX_SUPPRESSION_DAYS,
            clock);
    AuditReadService auditReader = new AuditReadService(auditTrail, accessLog, clock);

    URI jwks = issuer.startJwksServer();
    context.register(HarnessConfiguration.class);
    context.registerBean(PortfolioDashboardService.class, () -> portfolio);
    context.registerBean(SquadViewService.class, () -> squad);
    context.registerBean(QueryService.class, () -> query);
    context.registerBean(EvidenceRetrievalService.class, () -> evidenceService);
    context.registerBean(SquadSuppressionService.class, () -> suppressionService);
    context.registerBean(AuditReadService.class, () -> auditReader);
    context.registerBean(
        ReactiveJwtDecoder.class,
        () -> ControlPlaneJwtDecoders.create(jwks, TestJwtIssuer.ISSUER, TestJwtIssuer.AUDIENCE));
    context.refresh();
    this.client =
        WebTestClient.bindToApplicationContext(context)
            .apply(SecurityMockServerConfigurers.springSecurity())
            .configureClient()
            .responseTimeout(Duration.ofSeconds(30))
            .build();
  }

  // ---- seeding -----------------------------------------------------------------------------

  void onboard(String repository, String owner) {
    registry.save(
        new OnboardedRepository(
            repository, owner, "tier-1", "1.0.0", "STAGE_3", new WorkItemRoute("jira", "DH")));
  }

  static PseudonymisedFinding finding(String fp, Severity severity, String path) {
    return new PseudonymisedFinding(
        "f-" + fp,
        "codemaat.hotspot",
        Category.HOTSPOT,
        severity,
        0.8,
        path,
        7,
        "message for " + fp,
        Map.of("changeFrequency", 42.0),
        0.5,
        true,
        fp,
        List.of(Pseudonym.fromStored(CONTRIBUTOR)));
  }

  /** Stores a run with the findings it reported and records each finding's state. */
  void run(
      String runId,
      String repository,
      String bundleVersion,
      Instant committedAt,
      FindingState state,
      PseudonymisedFinding... reported) {
    runs.save(
        new RunRecord(
            runId,
            repository,
            "root",
            "c-" + runId,
            "0.1.0",
            "k-" + runId,
            false,
            false,
            List.of(new EngineRecord("code-maat", "OK")),
            committedAt,
            RunStatus.ACTIVE,
            null,
            "FAILED",
            committedAt,
            "FULL",
            bundleVersion));
    findings.saveRun(runId, repository, List.of(reported));
    for (PseudonymisedFinding f : reported) {
      states.putAll(
          List.of(new FindingStateRecord(repository, "root", f.fingerprint(), state, "c")));
      evidence.saveHot(
          EvidenceSnapshot.capture(runId, repository, f, committedAt, Duration.ofDays(30)));
    }
  }

  void bundle(String version, String hexDigitChar, String supersededBy) {
    policies.save(
        new PolicyBundleRecord(
            version, "sha256:" + hexDigitChar.repeat(64), "approver", NOW, NOW, supersededBy));
  }

  void snapshot(String repository, int critical, int high, String policy, Instant updatedAt) {
    readModel.commit(
        new ProjectionBatch(
            Map.of(),
            List.of(),
            List.of(
                new RunSnapshot(
                    repository,
                    "root",
                    "c-" + repository,
                    "run-" + repository,
                    NOW.minusSeconds(60),
                    critical,
                    high,
                    0,
                    0,
                    0,
                    0,
                    0.0,
                    policy)),
            List.of(),
            List.of(),
            List.of(),
            List.of()),
        updatedAt);
  }

  CoverageRepository coverageRepository() {
    return coverage;
  }

  // ---- tokens and calls --------------------------------------------------------------------

  String leader() {
    return issuer.tokenWithRolesAndSquads("lena", List.of(Roles.LEADER), List.of());
  }

  String squad(String... squads) {
    return issuer.tokenWithRolesAndSquads("alice", List.of(Roles.SQUAD), List.of(squads));
  }

  String investigator(String... squads) {
    return issuer.tokenWithRolesAndSquads("ivy", List.of(Roles.EVIDENCE), List.of(squads));
  }

  String auditor() {
    return issuer.tokenWithRolesAndSquads("ada", List.of(Roles.AUDITOR), List.of());
  }

  String token(List<String> roles, List<String> squads) {
    return issuer.tokenWithRolesAndSquads("multi", roles, squads);
  }

  WebTestClient.ResponseSpec get(String token, String path) {
    WebTestClient.RequestHeadersSpec<?> request = client.get().uri(URI.create(path));
    if (token != null) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    return request.exchange();
  }

  WebTestClient.ResponseSpec post(String token, String path, Object body) {
    WebTestClient.RequestBodySpec request =
        client.post().uri(URI.create(path)).contentType(MediaType.APPLICATION_JSON);
    if (token != null) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    return (body == null ? request : request.bodyValue(body)).exchange();
  }

  WebTestClient.ResponseSpec delete(String token, String path) {
    WebTestClient.RequestHeadersSpec<?> request = client.delete().uri(URI.create(path));
    if (token != null) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    return request.exchange();
  }

  @Override
  public void close() {
    context.close();
    issuer.close();
  }

  @Configuration
  @EnableWebFlux
  @Import({IngestionSecurityConfiguration.class, ServeApiConfiguration.class})
  static class HarnessConfiguration {}
}
