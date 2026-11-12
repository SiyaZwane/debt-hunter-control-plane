package com.debthunter.controlplane;

import com.debthunter.controlplane.api.admin.AdminApiConfiguration;
import com.debthunter.controlplane.api.ingestion.IngestionApiConfiguration;
import com.debthunter.controlplane.api.ingestion.RequestRateLimiter;
import com.debthunter.controlplane.api.ingestion.SlidingWindowRequestRateLimiter;
import com.debthunter.controlplane.api.security.AdminAccess;
import com.debthunter.controlplane.api.security.ControlPlaneJwtDecoders;
import com.debthunter.controlplane.api.security.IngestionSecurityConfiguration;
import com.debthunter.controlplane.egress.alert.LoggingSecurityAlertPublisher;
import com.debthunter.controlplane.egress.alert.SecurityAlertPublisher;
import com.debthunter.controlplane.ingest.intake.DurableRunIntakePublisher;
import com.debthunter.controlplane.ingest.intake.IntakeEnvelopeCodec;
import com.debthunter.controlplane.ingest.intake.IntakeQueue;
import com.debthunter.controlplane.ingest.intake.RunIntakeQueuePublisher;
import com.debthunter.controlplane.store.queue.AesGcmPayloadCipher;
import com.debthunter.controlplane.store.queue.JdbcWorkQueue;
import com.debthunter.controlplane.store.queue.PayloadCipher;
import com.debthunter.controlplane.store.queue.WorkQueue;
import java.net.URI;
import java.time.Clock;
import java.util.Base64;
import javax.sql.DataSource;
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
@Import({
  AdminApiConfiguration.class,
  AnalyticsConfiguration.class,
  IngestionApiConfiguration.class,
  IngestionSecurityConfiguration.class,
  StoreConfiguration.class,
  IngestPipelineConfiguration.class
})
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
   * Which role in a token grants administration of the control plane.
   *
   * @param properties the control-plane configuration
   * @return the access rule the security chain enforces on the administration routes
   */
  @Bean
  public AdminAccess adminAccess(ControlPlaneProperties properties) {
    return new AdminAccess(properties.security().adminRole());
  }

  /**
   * Encrypts what is queued. Refuses to be built without a usable key: a bundle holds real
   * contributor identities and must never sit in the database readable, and there is no fallback.
   *
   * @param properties the control-plane configuration
   * @return the cipher
   */
  @Bean
  public PayloadCipher intakePayloadCipher(ControlPlaneProperties properties) {
    String key = properties.intake().encryptionKey();
    if (isBlank(key)) {
      throw new IllegalStateException(
          "The intake encryption key is not configured. Set debthunter.controlplane.intake"
              + ".encryption-key (environment: CONTROL_PLANE_INTAKE_KEY) to at least 32"
              + " base64-encoded bytes.");
    }
    try {
      return new AesGcmPayloadCipher(Base64.getDecoder().decode(key.strip()));
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "debthunter.controlplane.intake.encryption-key must be base64-encoded and at least 32"
              + " bytes");
    }
  }

  /**
   * The durable work queue, in the system of record.
   *
   * @param dataSource the database
   * @param properties the control-plane configuration
   * @param clock the clock delays and leases are measured by
   * @return the queue
   */
  @Bean
  public WorkQueue workQueue(
      DataSource dataSource, ControlPlaneProperties properties, Clock clock) {
    ControlPlaneProperties.Intake intake = properties.intake();
    return new JdbcWorkQueue(
        dataSource,
        IntakeQueue.policies(
            intake.maxAttempts(), intake.lease(), intake.initialBackoff(), intake.maxBackoff()),
        clock);
  }

  /**
   * The intake queue publisher.
   *
   * @param queue the durable queue
   * @param cipher encrypts each bundle before it is stored
   * @return a publisher backed by the system of record
   */
  @Bean
  public RunIntakeQueuePublisher runIntakeQueuePublisher(WorkQueue queue, PayloadCipher cipher) {
    return new DurableRunIntakePublisher(queue, cipher, new IntakeEnvelopeCodec());
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

  /**
   * Works the intake queue and drives bundles through the pipeline. Off when {@code
   * intake.listener-enabled} is false, to run the ingestion edge alone.
   *
   * @param properties the control-plane configuration
   * @param queue the durable queue
   * @param cipher decrypts the bundles
   * @param receiver the head of the pipeline
   * @return the lifecycle component
   */
  @Bean
  @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(
      prefix = "debthunter.controlplane.intake",
      name = "listener-enabled",
      havingValue = "true",
      matchIfMissing = true)
  IntakeWorkerLifecycle intakeWorkerLifecycle(
      ControlPlaneProperties properties,
      WorkQueue queue,
      PayloadCipher cipher,
      com.debthunter.controlplane.ingest.receive.RunBundleReceiver receiver) {
    return new IntakeWorkerLifecycle(properties.intake(), queue, cipher, receiver);
  }

  /**
   * The periodic evidence-expiry and suppression-warning sweep.
   *
   * @param evidence evidence storage
   * @param suppressions the suppression register
   * @param queue the durable queue, whose old items are purged
   * @param auditRetention removes audit entries past their retention
   * @param clock the clock
   * @param properties the control-plane configuration
   * @return the lifecycle component
   */
  @Bean
  Housekeeping housekeeping(
      com.debthunter.controlplane.store.evidence.EvidenceStorageService evidence,
      com.debthunter.controlplane.store.suppressions.SuppressionRegisterService suppressions,
      WorkQueue queue,
      com.debthunter.controlplane.store.audit.AuditRetention auditRetention,
      Clock clock,
      ControlPlaneProperties properties) {
    return new Housekeeping(
        evidence,
        suppressions,
        queue,
        auditRetention,
        properties.intake().doneRetention(),
        properties.intake().deadRetention(),
        clock,
        properties.retention().sweepInterval());
  }

  private static boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
