package com.debthunter.controlplane.api.ingestion;

import com.debthunter.controlplane.egress.alert.SecurityAlertPublisher;
import com.debthunter.controlplane.ingest.intake.RunIntakeQueuePublisher;
import com.debthunter.controlplane.store.registry.RepositoryScopeLookup;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.codec.ServerCodecConfigurer;
import org.springframework.web.reactive.config.WebFluxConfigurer;

/**
 * Wires the ingestion edge. This module owns its own behaviour — the validators and the controller
 * — and takes everything that touches infrastructure (the queue, scope lookup, alerts, rate
 * limiting, the clock) as beans the application supplies, so each can be swapped without touching
 * the edge.
 */
@Configuration(proxyBeanMethods = false)
public class IngestionApiConfiguration {

  /**
   * The schema validator, accepting the configured current major version and the one before it.
   *
   * @param currentMajor the newest accepted schema major version
   * @return the validator
   */
  @Bean
  public RunBundleSchemaValidator runBundleSchemaValidator(
      @Value("${debthunter.controlplane.ingest.schema.current-major:1}") int currentMajor) {
    return RunBundleSchemaValidator.withClasspathSchemas(currentMajor);
  }

  /**
   * The workload-scope validator.
   *
   * @param scopes says which repositories each identity may write for
   * @return the validator
   */
  @Bean
  public WorkloadIdentityValidator workloadIdentityValidator(RepositoryScopeLookup scopes) {
    return new WorkloadIdentityValidator(scopes);
  }

  /**
   * The ingestion endpoint.
   *
   * @param schemaValidator checks bundles against the accepted schemas
   * @param workloadValidator checks the caller's scope
   * @param queue where accepted bundles go
   * @param alerts told when a caller steps outside its scope
   * @param rateLimiter limits how fast one caller may publish
   * @param clock stamps accepted bundles
   * @param blockingWait the longest the registry lookup or the queue write may take, as an ISO-8601
   *     duration (for example {@code PT3S})
   * @return the controller
   */
  @Bean
  public IngestionController ingestionController(
      RunBundleSchemaValidator schemaValidator,
      WorkloadIdentityValidator workloadValidator,
      RunIntakeQueuePublisher queue,
      SecurityAlertPublisher alerts,
      RequestRateLimiter rateLimiter,
      Clock clock,
      @Value("${debthunter.controlplane.intake.max-wait:PT3S}") String blockingWait) {
    return new IngestionController(
        schemaValidator,
        workloadValidator,
        queue,
        alerts,
        rateLimiter,
        clock,
        java.time.Duration.parse(blockingWait));
  }

  /**
   * Raises the in-memory limit for request bodies. WebFlux defaults to 256 KB, which a real bundle
   * for a large repository easily exceeds.
   *
   * @param maxBundleBytes the largest accepted bundle
   * @return the codec configurer
   */
  @Bean
  public WebFluxConfigurer ingestionCodecs(
      @Value("${debthunter.controlplane.ingest.max-bundle-bytes:10485760}") int maxBundleBytes) {
    return new WebFluxConfigurer() {
      @Override
      public void configureHttpMessageCodecs(ServerCodecConfigurer configurer) {
        configurer.defaultCodecs().maxInMemorySize(maxBundleBytes);
      }
    };
  }
}
