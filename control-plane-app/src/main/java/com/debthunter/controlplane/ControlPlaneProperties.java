package com.debthunter.controlplane;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything about the control plane that is configured rather than coded, bound from {@code
 * debthunter.controlplane.*}.
 *
 * @param security how bearer tokens are verified
 * @param intake where accepted bundles are queued, and how they are consumed
 * @param rateLimit how fast one pipeline may publish
 * @param database the system of record
 * @param pseudonym the secret that makes contributor pseudonyms unguessable
 * @param retention how long evidence detail is kept and how often housekeeping runs
 */
@ConfigurationProperties(prefix = "debthunter.controlplane")
public record ControlPlaneProperties(
    @DefaultValue Security security,
    @DefaultValue Intake intake,
    @DefaultValue RateLimit rateLimit,
    @DefaultValue Database database,
    @DefaultValue Pseudonym pseudonym,
    @DefaultValue Retention retention) {

  /**
   * Token verification. All three are required; the application refuses to start without them
   * rather than run unauthenticated.
   *
   * @param jwkSetUri where the issuer publishes its signing keys
   * @param issuer the {@code iss} claim a token must carry
   * @param audience the {@code aud} value that must be among a token's audiences
   * @param adminRole the value a token's {@code roles} claim must contain to administer the control
   *     plane; pipelines' tokens do not carry it
   */
  public record Security(
      String jwkSetUri,
      String issuer,
      String audience,
      @DefaultValue("ControlPlane.Admin") String adminRole) {}

  /**
   * The intake queue.
   *
   * @param bootstrapServers the Kafka cluster's bootstrap addresses
   * @param topic the intake topic
   * @param maxWait the longest a publish may block or wait, bounding how long a dead queue can hold
   *     up a request
   * @param consumerGroup the consumer group shared by every control-plane instance
   * @param listenerEnabled whether this instance consumes the queue; turn it off to run the
   *     ingestion edge alone
   */
  public record Intake(
      @DefaultValue("localhost:9092") String bootstrapServers,
      @DefaultValue("debt-hunter.run-intake") String topic,
      @DefaultValue("PT3S") Duration maxWait,
      @DefaultValue("debt-hunter-control-plane") String consumerGroup,
      @DefaultValue("true") boolean listenerEnabled) {}

  /**
   * The per-pipeline publish rate limit.
   *
   * @param maxRequests the most bundles one workload identity may publish per window
   * @param window the trailing window requests are counted over
   */
  public record RateLimit(
      @DefaultValue("600") int maxRequests, @DefaultValue("PT1M") Duration window) {}

  /**
   * The system of record. The URL is required: the application refuses to start without a database.
   *
   * @param url the JDBC URL
   * @param username the database user
   * @param password the database password
   * @param maxPoolSize the most connections the pool may open
   */
  public record Database(
      String url, String username, String password, @DefaultValue("10") int maxPoolSize) {}

  /**
   * The pseudonymisation key. Required: nothing is processed without it.
   *
   * @param key at least 32 bytes of key material, base64-encoded. This is the interim source; the
   *     Azure Key Vault binding replaces it
   */
  public record Pseudonym(String key) {}

  /**
   * Retention and housekeeping.
   *
   * @param evidenceHotTtl how long full evidence detail stays in the hot tier
   * @param sweepInterval how often expired evidence is moved to the warm tier and suppression
   *     warnings are raised
   */
  public record Retention(
      @DefaultValue("P30D") Duration evidenceHotTtl,
      @DefaultValue("PT1H") Duration sweepInterval) {}
}
