package com.debthunter.controlplane;

import com.debthunter.controlplane.egress.alert.OperationsAlertPublisher;
import com.debthunter.controlplane.egress.outbox.EgressCheckpoints;
import com.debthunter.controlplane.egress.outbox.JdbcEgressCheckpoints;
import com.debthunter.controlplane.egress.outbox.TransactionalOutbox;
import com.debthunter.controlplane.egress.workitem.AzureBoardsWorkItemAdapter;
import com.debthunter.controlplane.egress.workitem.JdbcWorkItemLinkRepository;
import com.debthunter.controlplane.egress.workitem.JiraWorkItemAdapter;
import com.debthunter.controlplane.egress.workitem.RunLinks;
import com.debthunter.controlplane.egress.workitem.WorkItemEgressWorker;
import com.debthunter.controlplane.egress.workitem.WorkItemFeeder;
import com.debthunter.controlplane.egress.workitem.WorkItemLinkRepository;
import com.debthunter.controlplane.egress.workitem.WorkItemOrchestrator;
import com.debthunter.controlplane.egress.workitem.WorkItemQueue;
import com.debthunter.controlplane.egress.workitem.WorkItemSettings;
import com.debthunter.controlplane.egress.workitem.WorkItemSyncHandler;
import com.debthunter.controlplane.egress.workitem.WorkItemTracker;
import com.debthunter.controlplane.egress.workitem.WorkItemTrackers;
import com.debthunter.controlplane.store.findings.PseudonymisedFindingStore;
import com.debthunter.controlplane.store.lifecycle.TransitionLogRepository;
import com.debthunter.controlplane.store.queue.WorkQueue;
import com.debthunter.controlplane.store.registry.RepositoryRegistry;
import com.debthunter.controlplane.store.rollout.StageLookup;
import com.debthunter.controlplane.store.suppressions.SuppressionRepository;
import java.net.URI;
import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Work-item egress: reads the transition log, queues a job per change and delivers them to the
 * trackers. Off when {@code zones.egress.enabled} is false. Nothing in ingest waits on it; if it is
 * stopped, work queues up in the system of record and is delivered when it starts again.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
    prefix = "debthunter.controlplane.zones.egress",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
class EgressConfiguration {

  @Bean
  WorkItemLinkRepository workItemLinkRepository(DataSource dataSource) {
    return new JdbcWorkItemLinkRepository(dataSource);
  }

  @Bean
  EgressCheckpoints egressCheckpoints(DataSource dataSource) {
    return new JdbcEgressCheckpoints(dataSource);
  }

  @Bean
  WorkItemTrackers workItemTrackers(ControlPlaneProperties properties) {
    ControlPlaneProperties.Trackers config = properties.zones().egress().trackers();
    Map<String, WorkItemTracker> trackers = new HashMap<>();
    if (config.azureBoards() != null) {
      ControlPlaneProperties.Tracker t = config.azureBoards();
      String credential = checked("azure-boards", t);
      trackers.put(
          "azure-boards",
          new AzureBoardsWorkItemAdapter(
              URI.create(t.baseUrl().strip()),
              () -> credential,
              t.timeout(),
              AzureBoardsWorkItemAdapter.StateNames.defaults()));
    }
    if (config.jira() != null) {
      ControlPlaneProperties.Tracker t = config.jira();
      String credential = checked("jira", t);
      trackers.put(
          "jira",
          new JiraWorkItemAdapter(
              URI.create(t.baseUrl().strip()),
              () -> credential,
              t.timeout(),
              JiraWorkItemAdapter.StateNames.defaults()));
    }
    return new WorkItemTrackers(trackers);
  }

  @Bean
  RunLinks runLinks(ControlPlaneProperties properties) {
    String base = properties.zones().egress().runLinkBase();
    if (base == null || base.isBlank()) {
      return RunLinks.none();
    }
    String prefix = base.strip().endsWith("/") ? base.strip() : base.strip() + "/";
    return runId ->
        java.util.Optional.of(
            prefix + java.net.URLEncoder.encode(runId, java.nio.charset.StandardCharsets.UTF_8));
  }

  @Bean
  WorkerZone egressZone(
      ControlPlaneProperties properties,
      TransitionLogRepository log,
      WorkItemLinkRepository links,
      PseudonymisedFindingStore findings,
      SuppressionRepository suppressions,
      RepositoryRegistry registry,
      StageLookup stages,
      WorkItemTrackers trackers,
      RunLinks runLinks,
      EgressCheckpoints checkpoints,
      WorkQueue queue,
      OperationsAlertPublisher alerts,
      Clock clock) {
    ControlPlaneProperties.Egress egress = properties.zones().egress();
    ControlPlaneProperties.Delivery delivery = egress.delivery();
    WorkItemSettings settings = new WorkItemSettings(egress.minimumSeverity(), egress.claimTtl());
    return new WorkerZone(
        "egress",
        () -> {
          String id = "egress-" + UUID.randomUUID();
          TransactionalOutbox outbox =
              new TransactionalOutbox(
                  queue, WorkItemQueue.KIND, id, delivery.batchSize(), alerts, clock);
          WorkItemOrchestrator orchestrator =
              new WorkItemOrchestrator(
                  log,
                  links,
                  findings,
                  suppressions,
                  registry,
                  stages,
                  trackers,
                  runLinks,
                  settings,
                  clock,
                  id);
          WorkItemEgressWorker worker =
              new WorkItemEgressWorker(
                  new WorkItemFeeder(log, checkpoints, outbox, delivery.batchSize()),
                  outbox,
                  new WorkItemSyncHandler(orchestrator),
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

  /** Queue policies for the egress kinds; always registered so the queue accepts them. */
  static Map<String, com.debthunter.controlplane.store.queue.QueuePolicy> queuePolicies(
      ControlPlaneProperties.Delivery d) {
    return WorkItemQueue.policies(d.maxAttempts(), d.lease(), d.initialBackoff(), d.maxBackoff());
  }

  private static String checked(String name, ControlPlaneProperties.Tracker t) {
    String prefix = "debthunter.controlplane.zones.egress.trackers." + name;
    if (t.baseUrl() == null || t.baseUrl().isBlank()) {
      throw new IllegalStateException(prefix + ".base-url is not set");
    }
    if (t.authorization() == null || t.authorization().isBlank()) {
      throw new IllegalStateException(prefix + ".authorization is not set");
    }
    URI uri;
    try {
      uri = URI.create(t.baseUrl().strip());
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(prefix + ".base-url is not a valid address");
    }
    boolean local =
        "localhost".equalsIgnoreCase(uri.getHost()) || "127.0.0.1".equals(uri.getHost());
    if (!"https".equalsIgnoreCase(uri.getScheme()) && !(local && "http".equals(uri.getScheme()))) {
      throw new IllegalStateException(
          prefix + ".base-url must be https, so the credential is never sent in the clear");
    }
    return t.authorization().strip();
  }
}
