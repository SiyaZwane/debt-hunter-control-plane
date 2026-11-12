package com.debthunter.controlplane.egress.workitem;

import com.debthunter.controlplane.egress.outbox.DeliveryUnavailableException;
import com.debthunter.controlplane.egress.outbox.OutboxHandler;
import com.debthunter.controlplane.egress.outbox.OutboxMessage;
import com.debthunter.controlplane.egress.outbox.UndeliverableException;
import com.debthunter.controlplane.store.jdbc.StoreException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Delivers a work-item message by asking the orchestrator to look at the finding. A message that
 * cannot be read is set aside at once; a system-of-record outage is not the message's fault, so it
 * is held.
 */
public final class WorkItemSyncHandler implements OutboxHandler {

  private static final Logger LOG = LoggerFactory.getLogger(WorkItemSyncHandler.class);

  private final WorkItemOrchestrator orchestrator;

  /**
   * Creates the handler.
   *
   * @param orchestrator does the work
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public WorkItemSyncHandler(WorkItemOrchestrator orchestrator) {
    this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
  }

  @Override
  public void deliver(OutboxMessage message) {
    SyncJob job;
    try {
      job = SyncJob.decode(message.payload());
    } catch (IllegalArgumentException e) {
      throw new UndeliverableException("the message is not a work-item job");
    }
    try {
      SyncResult result = orchestrator.reconcile(job);
      LOG.debug("work item for {} {}: {}", job.repository(), job.eventId(), result);
    } catch (StoreException e) {
      throw new DeliveryUnavailableException("the system of record is unavailable", e);
    }
  }
}
