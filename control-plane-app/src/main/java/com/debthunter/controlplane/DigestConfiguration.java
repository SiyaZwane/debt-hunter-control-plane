package com.debthunter.controlplane;

import com.debthunter.controlplane.egress.alert.OperationsAlertPublisher;
import com.debthunter.controlplane.egress.digest.DigestBuilder;
import com.debthunter.controlplane.egress.digest.DigestChannel;
import com.debthunter.controlplane.egress.digest.DigestLedger;
import com.debthunter.controlplane.egress.digest.DigestNotificationService;
import com.debthunter.controlplane.egress.digest.DigestQueue;
import com.debthunter.controlplane.egress.digest.DigestSendHandler;
import com.debthunter.controlplane.egress.digest.DigestSettings;
import com.debthunter.controlplane.egress.digest.DigestWorker;
import com.debthunter.controlplane.egress.digest.JdbcDigestLedger;
import com.debthunter.controlplane.egress.digest.JdbcOpenFindingCounts;
import com.debthunter.controlplane.egress.digest.JdbcSquadChannelRepository;
import com.debthunter.controlplane.egress.digest.SlackNotificationAdapter;
import com.debthunter.controlplane.egress.digest.SquadChannel;
import com.debthunter.controlplane.egress.digest.SquadChannelRepository;
import com.debthunter.controlplane.egress.digest.TeamsNotificationAdapter;
import com.debthunter.controlplane.egress.digest.WebhookSecrets;
import com.debthunter.controlplane.egress.outbox.TransactionalOutbox;
import com.debthunter.controlplane.store.findings.PseudonymisedFindingStore;
import com.debthunter.controlplane.store.lifecycle.TransitionLogRepository;
import com.debthunter.controlplane.store.queue.QueuePolicy;
import com.debthunter.controlplane.store.queue.WorkQueue;
import com.debthunter.controlplane.store.registry.RepositoryRegistry;
import com.debthunter.controlplane.store.rollout.StageLookup;
import com.debthunter.controlplane.store.suppressions.SuppressionRepository;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The periodic digest to each squad's channel. Off unless {@code zones.digests.enabled} is true. It
 * needs a link base to point squads at, and refuses to start without one.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "debthunter.controlplane.zones.digests",
    name = "enabled",
    havingValue = "true")
class DigestConfiguration {

  @Bean
  DigestLedger digestLedger(DataSource dataSource) {
    return new JdbcDigestLedger(dataSource);
  }

  @Bean
  SquadChannelRepository squadChannelRepository(DataSource dataSource) {
    return new JdbcSquadChannelRepository(dataSource);
  }

  @Bean
  WebhookSecrets webhookSecrets(ControlPlaneProperties properties) {
    Map<String, String> webhooks = properties.zones().digests().webhooks();
    Map<String, String> held = webhooks == null ? Map.of() : Map.copyOf(webhooks);
    return ref -> Optional.ofNullable(held.get(ref)).filter(v -> !v.isBlank()).map(URI::create);
  }

  @Bean
  WorkerZone digestZone(
      ControlPlaneProperties properties,
      RepositoryRegistry registry,
      TransitionLogRepository log,
      PseudonymisedFindingStore findings,
      SuppressionRepository suppressions,
      StageLookup stages,
      DigestLedger ledger,
      SquadChannelRepository channels,
      WebhookSecrets secrets,
      DataSource dataSource,
      WorkQueue queue,
      OperationsAlertPublisher alerts,
      Clock clock) {
    ControlPlaneProperties.Digests digests = properties.zones().digests();
    String base = digests.squadLinkBase();
    if (base == null || base.isBlank()) {
      throw new IllegalStateException(
          "debthunter.controlplane.zones.digests.squad-link-base is not set; a digest needs"
              + " somewhere to point each squad");
    }
    String prefix = base.strip().endsWith("/") ? base.strip() : base.strip() + "/";
    DigestSettings defaults = DigestSettings.defaults();
    DigestSettings settings =
        new DigestSettings(
            digests.cadence(),
            defaults.expiryHorizon(),
            defaults.maxNotable(),
            defaults.maxStages(),
            defaults.maxText(),
            digests.sendEmpty());
    ControlPlaneProperties.Delivery delivery = digests.delivery();
    Duration timeout = Duration.ofSeconds(20);
    Map<SquadChannel.Kind, DigestChannel> adapters =
        Map.of(
            SquadChannel.Kind.SLACK, new SlackNotificationAdapter(timeout),
            SquadChannel.Kind.TEAMS, new TeamsNotificationAdapter(timeout));
    return new WorkerZone(
        "digests",
        () -> {
          String id = "digest-" + UUID.randomUUID();
          TransactionalOutbox outbox =
              new TransactionalOutbox(
                  queue, DigestQueue.KIND, id, delivery.batchSize(), alerts, clock);
          DigestBuilder builder =
              new DigestBuilder(
                  log,
                  findings,
                  suppressions,
                  stages,
                  new JdbcOpenFindingCounts(dataSource),
                  owner -> prefix + URLEncoder.encode(owner, StandardCharsets.UTF_8),
                  settings,
                  clock);
          DigestWorker worker =
              new DigestWorker(
                  new DigestNotificationService(
                      registry, log, ledger, builder, outbox, settings, clock),
                  outbox,
                  new DigestSendHandler(ledger, channels, secrets, adapters, clock),
                  delivery.pollInterval());
          return new WorkerZone.Worker() {
            @Override
            public void start() {
              worker.start();
            }

            @Override
            public void close() {
              worker.close();
            }
          };
        });
  }

  /** Queue policies for the digest kind; always registered so the queue accepts it. */
  static Map<String, QueuePolicy> queuePolicies(ControlPlaneProperties.Delivery d) {
    return DigestQueue.policies(d.maxAttempts(), d.lease(), d.initialBackoff(), d.maxBackoff());
  }
}
