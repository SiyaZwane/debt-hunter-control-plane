package com.debthunter.controlplane;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Everything about the control plane that is configured rather than coded, bound from {@code
 * debthunter.controlplane.*}.
 *
 * @param security how bearer tokens are verified
 * @param intake where accepted bundles are queued
 * @param rateLimit how fast one pipeline may publish
 * @param workloadScopes each workload identity mapped to the repositories it may publish for; a
 *     stand-in for the repository registry until that exists
 */
@ConfigurationProperties(prefix = "debthunter.controlplane")
public record ControlPlaneProperties(
    @DefaultValue Security security,
    @DefaultValue Intake intake,
    @DefaultValue RateLimit rateLimit,
    @DefaultValue Map<String, List<String>> workloadScopes) {

  /**
   * Token verification. All three are required; the application refuses to start without them
   * rather than run unauthenticated.
   *
   * @param jwkSetUri where the issuer publishes its signing keys
   * @param issuer the {@code iss} claim a token must carry
   * @param audience the {@code aud} value that must be among a token's audiences
   */
  public record Security(String jwkSetUri, String issuer, String audience) {}

  /**
   * The intake queue.
   *
   * @param bootstrapServers the Kafka cluster's bootstrap addresses
   * @param topic the intake topic
   * @param maxWait the longest a publish may block or wait, bounding how long a dead queue can hold
   *     up a request
   */
  public record Intake(
      @DefaultValue("localhost:9092") String bootstrapServers,
      @DefaultValue("debt-hunter.run-intake") String topic,
      @DefaultValue("PT3S") Duration maxWait) {}

  /**
   * The per-pipeline publish rate limit.
   *
   * @param maxRequests the most bundles one workload identity may publish per window
   * @param window the trailing window requests are counted over
   */
  public record RateLimit(
      @DefaultValue("600") int maxRequests, @DefaultValue("PT1M") Duration window) {}
}
