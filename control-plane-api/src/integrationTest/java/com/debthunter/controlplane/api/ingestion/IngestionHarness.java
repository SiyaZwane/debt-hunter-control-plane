package com.debthunter.controlplane.api.ingestion;

import com.debthunter.controlplane.api.security.ControlPlaneJwtDecoders;
import com.debthunter.controlplane.api.security.IngestionSecurityConfiguration;
import com.debthunter.controlplane.egress.alert.SecurityAlertPublisher;
import com.debthunter.controlplane.ingest.intake.RunIntakeQueuePublisher;
import com.debthunter.controlplane.store.registry.InMemoryRepositoryScopeLookup;
import com.debthunter.controlplane.store.registry.RepositoryScopeLookup;
import com.debthunter.controlplane.testkit.TestJwtIssuer;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
 * Assembles the ingestion edge as a real Spring application context — the real security filter
 * chain, the production JWT decoder pointed at a local JWKS endpoint, the real validators and
 * controller — with only the infrastructure behind it (queue, alerting, rate limiting, scope data)
 * supplied by the test. No Spring Boot is involved: this module must not depend on it.
 */
final class IngestionHarness implements AutoCloseable {

  static final String SERVICE = "svc-a";
  static final String REPOSITORY = "acme/widgets";

  final TestJwtIssuer issuer = new TestJwtIssuer();
  final CapturingQueue queue = new CapturingQueue();
  final RecordingAlerts alerts = new RecordingAlerts();
  final TestClock clock = new TestClock(Instant.parse("2026-02-03T04:05:06Z"));
  final WebTestClient client;

  private final AnnotationConfigApplicationContext context =
      new AnnotationConfigApplicationContext();

  private IngestionHarness(Builder builder) {
    URI jwks = issuer.startJwksServer();
    context.register(HarnessConfiguration.class);
    context.registerBean("clock", Clock.class, () -> clock);
    context.registerBean(
        RepositoryScopeLookup.class,
        () ->
            builder.scopeLookup != null
                ? builder.scopeLookup
                : new InMemoryRepositoryScopeLookup(builder.scopes));
    context.registerBean(
        RunIntakeQueuePublisher.class, () -> builder.queue != null ? builder.queue : queue);
    context.registerBean(SecurityAlertPublisher.class, () -> alerts);
    context.registerBean(
        RequestRateLimiter.class,
        () ->
            builder.rateLimiter != null ? builder.rateLimiter : key -> RateLimitDecision.permit());
    context.registerBean(
        ReactiveJwtDecoder.class,
        () -> ControlPlaneJwtDecoders.create(jwks, TestJwtIssuer.ISSUER, TestJwtIssuer.AUDIENCE));
    if (builder.schemaValidator != null) {
      // Primary, so it wins over the validator the application configuration defines.
      context.registerBean(
          "overriddenSchemaValidator",
          RunBundleSchemaValidator.class,
          () -> builder.schemaValidator,
          definition -> definition.setPrimary(true));
    }
    context.refresh();
    this.client =
        WebTestClient.bindToApplicationContext(context)
            .apply(SecurityMockServerConfigurers.springSecurity())
            .configureClient()
            .responseTimeout(Duration.ofSeconds(30))
            .build();
  }

  static Builder builder() {
    return new Builder();
  }

  /** A signed, valid token for a workload identity. */
  String tokenFor(String identity) {
    return issuer.token(identity);
  }

  /** Publishes a bundle as the given bearer token. */
  WebTestClient.ResponseSpec publish(String bearerToken, String body) {
    WebTestClient.RequestBodySpec request =
        client.post().uri(IngestionController.PATH).contentType(MediaType.APPLICATION_JSON);
    if (bearerToken != null) {
      request.header(HttpHeaders.AUTHORIZATION, "Bearer " + bearerToken);
    }
    return request.bodyValue(body).exchange();
  }

  @Override
  public void close() {
    context.close();
    issuer.close();
  }

  @Configuration
  @EnableWebFlux
  @Import({IngestionApiConfiguration.class, IngestionSecurityConfiguration.class})
  static class HarnessConfiguration {}

  static final class Builder {
    private Map<String, List<String>> scopes = Map.of(SERVICE, List.of(REPOSITORY));
    private RunIntakeQueuePublisher queue;
    private RepositoryScopeLookup scopeLookup;
    private RequestRateLimiter rateLimiter;
    private RunBundleSchemaValidator schemaValidator;

    Builder scopes(Map<String, List<String>> scopes) {
      this.scopes = scopes;
      return this;
    }

    Builder scopeLookup(RepositoryScopeLookup scopeLookup) {
      this.scopeLookup = scopeLookup;
      return this;
    }

    Builder queue(RunIntakeQueuePublisher queue) {
      this.queue = queue;
      return this;
    }

    Builder rateLimiter(RequestRateLimiter rateLimiter) {
      this.rateLimiter = rateLimiter;
      return this;
    }

    Builder schemaValidator(RunBundleSchemaValidator schemaValidator) {
      this.schemaValidator = schemaValidator;
      return this;
    }

    IngestionHarness start() {
      return new IngestionHarness(this);
    }
  }
}
