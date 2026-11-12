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
 * @param analytics how the analytics read models are kept up to date
 */
@ConfigurationProperties(prefix = "debthunter.controlplane")
public record ControlPlaneProperties(
    @DefaultValue Security security,
    @DefaultValue Intake intake,
    @DefaultValue RateLimit rateLimit,
    @DefaultValue Database database,
    @DefaultValue Pseudonym pseudonym,
    @DefaultValue Retention retention,
    @DefaultValue Analytics analytics) {

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
   * The intake queue, kept in the system of record.
   *
   * @param maxWait the longest the registry lookup or the queue write may take before the caller is
   *     told to try again; also how long a request is held up by a database that is slow or down
   * @param listenerEnabled whether this instance works the queue; turn it off to run the ingestion
   *     edge alone
   * @param encryptionKey base64, at least 32 bytes. Encrypts each bundle in the queue, because it
   *     still holds real contributor identities. Required. This is the interim source; the Azure
   *     Key Vault binding replaces it
   * @param maxAttempts how many times a bundle that fails for its own reasons is tried before it is
   *     set aside as dead. A bundle held up by a key store or database outage is never counted
   * @param lease how long a worker may hold a bundle before it is presumed lost and handed to
   *     another
   * @param initialBackoff the wait after a bundle's first failure
   * @param maxBackoff the longest wait between attempts, and how long a bundle is held while a
   *     dependency is down
   * @param batchSize how many bundles a worker takes at a time
   * @param pollInterval how long a worker waits when the queue is empty
   * @param doneRetention how long finished items' bookkeeping is kept
   * @param deadRetention how long a bundle that was set aside is kept (still encrypted) for
   *     investigation
   */
  public record Intake(
      @DefaultValue("PT3S") Duration maxWait,
      @DefaultValue("true") boolean listenerEnabled,
      String encryptionKey,
      @DefaultValue("10") int maxAttempts,
      @DefaultValue("PT5M") Duration lease,
      @DefaultValue("PT1S") Duration initialBackoff,
      @DefaultValue("PT30S") Duration maxBackoff,
      @DefaultValue("20") int batchSize,
      @DefaultValue("PT0.5S") Duration pollInterval,
      @DefaultValue("P1D") Duration doneRetention,
      @DefaultValue("P7D") Duration deadRetention) {}

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
   * @param connectionTimeout how long a caller waits for a connection from the pool before failing
   * @param socketTimeout how long a database call may wait for the server before it fails, so a
   *     database that stops answering cannot hold a thread for ever
   */
  public record Database(
      String url,
      String username,
      String password,
      @DefaultValue("10") int maxPoolSize,
      @DefaultValue("PT3S") Duration connectionTimeout,
      @DefaultValue("PT30S") Duration socketTimeout) {}

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

  /**
   * Analytics read models. They are derived from the system of record, built asynchronously, and
   * never consulted by ingest.
   *
   * @param projectorEnabled whether this instance projects; turning it off leaves the read models
   *     to go stale and changes nothing about ingest
   * @param projectionInterval how often the projector catches up
   * @param staleAfter how old the read models may be before query results are flagged stale
   * @param batchSize how many runs or transitions are projected and committed at a time
   * @param coverage coverage and adoption tracking
   */
  public record Analytics(
      @DefaultValue("true") boolean projectorEnabled,
      @DefaultValue("PT30S") Duration projectionInterval,
      @DefaultValue("PT15M") Duration staleAfter,
      @DefaultValue("500") int batchSize,
      @DefaultValue Coverage coverage) {}

  /**
   * Coverage and adoption tracking: reconciling the registry against the source-control estate.
   *
   * @param enabled whether this instance reconciles on a schedule
   * @param reconcileInterval how often (default nightly)
   * @param initialDelay how long after start-up the first reconciliation runs
   * @param cadence how long an onboarded repository may go without a run before it is silent
   * @param currentToolMajor the data-plane major version that counts as current; anything else is
   *     version drift
   * @param degradedWindow how many of a repository's latest runs must all be degraded or shallow
   *     for it to count as consistently so
   * @param staleAfter how old the stored report may be before it is flagged stale
   * @param estate which source-control estate to compare against
   */
  public record Coverage(
      @DefaultValue("true") boolean enabled,
      @DefaultValue("P1D") Duration reconcileInterval,
      @DefaultValue("PT1M") Duration initialDelay,
      @DefaultValue("P7D") Duration cadence,
      @DefaultValue("0") int currentToolMajor,
      @DefaultValue("3") int degradedWindow,
      @DefaultValue("PT36H") Duration staleAfter,
      @DefaultValue Estate estate) {}

  /**
   * The source-control estate. With no provider configured, silence and drift are still detected
   * but repositories that were never onboarded cannot be found, and every report says so.
   *
   * @param provider {@code none}, {@code github} or {@code azure}
   * @param organization the GitHub organisation or Azure DevOps organisation to list
   * @param token a GitHub token or Azure DevOps personal access token able to read repositories
   * @param apiUrl the API base URL; blank for the public one
   * @param timeout connect and request timeout
   */
  public record Estate(
      @DefaultValue("none") String provider,
      String organization,
      String token,
      String apiUrl,
      @DefaultValue("PT20S") Duration timeout) {}
}
