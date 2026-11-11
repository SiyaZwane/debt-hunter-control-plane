package com.debthunter.controlplane.api.admin;

import com.debthunter.controlplane.api.ingestion.IngestionApiConfiguration;
import com.debthunter.controlplane.api.ingestion.IngestionController;
import com.debthunter.controlplane.api.ingestion.RateLimitDecision;
import com.debthunter.controlplane.api.ingestion.RequestRateLimiter;
import com.debthunter.controlplane.api.security.ControlPlaneJwtDecoders;
import com.debthunter.controlplane.api.security.IngestionSecurityConfiguration;
import com.debthunter.controlplane.egress.alert.SecurityAlertPublisher;
import com.debthunter.controlplane.ingest.intake.IntakeEnvelope;
import com.debthunter.controlplane.ingest.intake.RunIntakeQueuePublisher;
import com.debthunter.controlplane.store.registry.InMemoryRepositoryRegistry;
import com.debthunter.controlplane.store.registry.RepositoryRegistryService;
import com.debthunter.controlplane.testkit.TestJwtIssuer;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.test.web.reactive.server.SecurityMockServerConfigurers;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.reactive.config.EnableWebFlux;

/**
 * The administration API and the ingestion edge in one real Spring context — the real security
 * chain, the production JWT decoder, the real controllers — sharing one repository registry, so a
 * test can onboard a repository over HTTP and then publish to it over HTTP.
 */
final class AdminHarness implements AutoCloseable {

  static final String ADMIN = "admin-alice";
  static final String PIPELINE = "svc-pipeline";

  final TestJwtIssuer issuer = new TestJwtIssuer();
  final RepositoryRegistryService registry =
      new RepositoryRegistryService(new InMemoryRepositoryRegistry());
  final List<AdminAuditEntry> auditTrail = new CopyOnWriteArrayList<>();
  final List<IntakeEnvelope> published = new CopyOnWriteArrayList<>();
  final WebTestClient client;

  private final AnnotationConfigApplicationContext context =
      new AnnotationConfigApplicationContext();

  AdminHarness() {
    this(auditTrail -> {});
  }

  AdminHarness(AdminAuditLog extraAudit) {
    URI jwks = issuer.startJwksServer();
    context.register(HarnessConfiguration.class);
    context.registerBean(
        "clock",
        Clock.class,
        () -> Clock.fixed(Instant.parse("2026-02-03T04:05:06Z"), ZoneOffset.UTC));
    context.registerBean(RepositoryRegistryService.class, () -> registry);
    context.registerBean(
        AdminAuditLog.class,
        () ->
            entry -> {
              extraAudit.record(entry); // may throw, to simulate a broken audit trail
              auditTrail.add(entry);
            });
    context.registerBean(
        RunIntakeQueuePublisher.class,
        () ->
            envelope -> {
              published.add(envelope);
              return CompletableFuture.completedFuture(null);
            });
    context.registerBean(SecurityAlertPublisher.class, () -> alert -> {});
    context.registerBean(RequestRateLimiter.class, () -> key -> RateLimitDecision.permit());
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

  String adminToken() {
    return issuer.tokenWithRoles(ADMIN, IngestionSecurityConfiguration.DEFAULT_ADMIN_ROLE);
  }

  String pipelineToken() {
    return issuer.token(PIPELINE);
  }

  WebTestClient.ResponseSpec admin(HttpMethod method, String path, Object body) {
    return call(method, path, adminToken(), body);
  }

  WebTestClient.ResponseSpec call(HttpMethod method, String path, String token, Object body) {
    WebTestClient.RequestBodySpec request = client.method(method).uri(path);
    if (token != null) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
    request.contentType(MediaType.APPLICATION_JSON);
    return (body == null ? request : request.bodyValue(body)).exchange();
  }

  WebTestClient.ResponseSpec publish(String token, String bundle) {
    return client
        .post()
        .uri(IngestionController.PATH)
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(bundle)
        .exchange();
  }

  static String widgets() {
    return """
        {"repository":"acme/widgets","owner":"team-a","tier":"tier-1","policyBundleVersion":"1.0",\
        "rolloutStage":"enforce","route":{"tracker":"jira","target":"WID"}}""";
  }

  static String entry(String repository) {
    return widgets().replace("acme/widgets", repository);
  }

  @Override
  public void close() {
    context.close();
    issuer.close();
  }

  @Configuration
  @EnableWebFlux
  @Import({
    IngestionApiConfiguration.class,
    IngestionSecurityConfiguration.class,
    AdminApiConfiguration.class
  })
  static class HarnessConfiguration {}
}
