package com.debthunter.controlplane;

import com.debthunter.controlplane.api.ingestion.IngestionApiConfiguration;
import com.debthunter.controlplane.api.ingestion.RequestRateLimiter;
import com.debthunter.controlplane.api.ingestion.SlidingWindowRequestRateLimiter;
import com.debthunter.controlplane.api.security.ControlPlaneJwtDecoders;
import com.debthunter.controlplane.api.security.IngestionSecurityConfiguration;
import com.debthunter.controlplane.egress.alert.LoggingSecurityAlertPublisher;
import com.debthunter.controlplane.egress.alert.SecurityAlertPublisher;
import com.debthunter.controlplane.ingest.intake.KafkaRunIntakeQueuePublisher;
import com.debthunter.controlplane.ingest.intake.RunIntakeQueuePublisher;
import com.debthunter.controlplane.store.registry.InMemoryRepositoryScopeLookup;
import com.debthunter.controlplane.store.registry.RepositoryScopeLookup;
import java.net.URI;
import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;

/**
 * Assembles the control plane: supplies the infrastructure the framework-free modules are written
 * against. The modules own their behaviour; this class only decides which implementation of each
 * port to plug in, from configuration.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ControlPlaneProperties.class)
@Import({IngestionApiConfiguration.class, IngestionSecurityConfiguration.class})
public class ControlPlaneConfiguration {

  /**
   * The clock every time-dependent component reads, so none reaches for the wall clock itself.
   *
   * @return the system UTC clock
   */
  @Bean
  public Clock clock() {
    return Clock.systemUTC();
  }

  /**
   * The decoder that authenticates workload tokens. Refuses to be built without its configuration,
   * so a misconfigured deployment fails at start-up rather than running open.
   *
   * @param properties the control-plane configuration
   * @return the decoder
   */
  @Bean
  public ReactiveJwtDecoder reactiveJwtDecoder(ControlPlaneProperties properties) {
    ControlPlaneProperties.Security security = properties.security();
    if (isBlank(security.jwkSetUri())
        || isBlank(security.issuer())
        || isBlank(security.audience())) {
      throw new IllegalStateException(
          "Token verification is not configured. Set debthunter.controlplane.security.jwk-set-uri,"
              + " .issuer and .audience (environment: CONTROL_PLANE_JWK_SET_URI,"
              + " CONTROL_PLANE_JWT_ISSUER, CONTROL_PLANE_JWT_AUDIENCE).");
    }
    return ControlPlaneJwtDecoders.create(
        URI.create(security.jwkSetUri()), security.issuer(), security.audience());
  }

  /**
   * Which repositories each workload identity may publish for.
   *
   * @param properties the control-plane configuration
   * @return the scope lookup
   */
  @Bean
  public RepositoryScopeLookup repositoryScopeLookup(ControlPlaneProperties properties) {
    return new InMemoryRepositoryScopeLookup(properties.workloadScopes());
  }

  /**
   * The intake queue publisher. Closed with the application context.
   *
   * @param properties the control-plane configuration
   * @return a Kafka-backed publisher
   */
  @Bean
  public RunIntakeQueuePublisher runIntakeQueuePublisher(ControlPlaneProperties properties) {
    ControlPlaneProperties.Intake intake = properties.intake();
    return KafkaRunIntakeQueuePublisher.connect(
        intake.bootstrapServers(), intake.topic(), intake.maxWait());
  }

  /**
   * Where security alerts go.
   *
   * @return a publisher that logs alerts
   */
  @Bean
  public SecurityAlertPublisher securityAlertPublisher() {
    return new LoggingSecurityAlertPublisher();
  }

  /**
   * The per-pipeline publish rate limit.
   *
   * @param properties the control-plane configuration
   * @param clock the clock requests are timestamped against
   * @return the limiter
   */
  @Bean
  public RequestRateLimiter requestRateLimiter(ControlPlaneProperties properties, Clock clock) {
    ControlPlaneProperties.RateLimit limit = properties.rateLimit();
    return new SlidingWindowRequestRateLimiter(limit.maxRequests(), limit.window(), clock);
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
