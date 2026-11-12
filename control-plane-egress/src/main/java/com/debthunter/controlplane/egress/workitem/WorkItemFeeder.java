package com.debthunter.controlplane.egress.workitem;

import com.debthunter.controlplane.egress.outbox.EgressCheckpoints;
import com.debthunter.controlplane.egress.outbox.TransactionalOutbox;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.lifecycle.FindingTransitionEvent;
import com.debthunter.controlplane.store.lifecycle.SequencedTransition;
import com.debthunter.controlplane.store.lifecycle.TransitionLogRepository;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Turns the transition log into work for the outbox.
 *
 * <p>The log is written, durably, before anything depends on it, and the feeder only reads it, so
 * ingest never waits on a tracker and a tracker that is down only makes jobs queue. The feeder
 * remembers how far it has read; if it cannot queue a job it stops there and does not move on, so
 * the event is offered again on the next pass. Offering twice is harmless: a job carries its
 * event's id as an idempotency key, and the worker brings the tracker into line with the finding's
 * state whatever the number of jobs.
 */
public final class WorkItemFeeder {

  /** The feeder's name in the checkpoint table. */
  public static final String CHECKPOINT = "work-item-feeder";

  /** The states a work item reacts to. A finding's first sighting (NEW) is not one of them. */
  private static final Set<FindingState> RELEVANT =
      Set.of(
          FindingState.OPEN,
          FindingState.REGRESSED,
          FindingState.RESOLVED,
          FindingState.SUPPRESSED);

  private final TransitionLogRepository log;
  private final EgressCheckpoints checkpoints;
  private final TransactionalOutbox outbox;
  private final int batchSize;

  /**
   * Creates the feeder.
   *
   * @param log the transition log
   * @param checkpoints where the feeder's position is kept
   * @param outbox where jobs are queued
   * @param batchSize the most events read at a time
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public WorkItemFeeder(
      TransitionLogRepository log,
      EgressCheckpoints checkpoints,
      TransactionalOutbox outbox,
      int batchSize) {
    this.log = Objects.requireNonNull(log, "log");
    this.checkpoints = Objects.requireNonNull(checkpoints, "checkpoints");
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be at least 1");
    }
    this.batchSize = batchSize;
  }

  /**
   * Reads the next batch of the log and queues a job for each event that concerns a work item.
   *
   * @return how many log events were read; zero means the feeder is up to date
   * @throws RuntimeException if a job could not be queued; the position is left where it was
   */
  public int feed() {
    long from = checkpoints.read(CHECKPOINT);
    List<SequencedTransition> batch = log.after(from, batchSize);
    for (SequencedTransition next : batch) {
      FindingTransitionEvent event = next.event();
      if (RELEVANT.contains(event.toState())) {
        SyncJob job =
            new SyncJob(
                event.repository(),
                event.projectSlice(),
                event.fingerprint(),
                event.runId(),
                event.eventId());
        outbox.submit(job.queueKey(), event.eventId(), job.encode());
      }
    }
    if (!batch.isEmpty()) {
      checkpoints.advance(CHECKPOINT, batch.get(batch.size() - 1).seq());
    }
    return batch.size();
  }
}
