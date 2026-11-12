package com.debthunter.controlplane.egress.workitem;

import com.debthunter.controlplane.egress.outbox.DrainResult;
import com.debthunter.controlplane.egress.outbox.OutboxHandler;
import com.debthunter.controlplane.egress.outbox.TransactionalOutbox;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs work-item egress: reads the transition log into the outbox, then delivers what the outbox
 * holds. Nothing waits on it. If it is stopped the log is still written and the outbox still
 * accepts, so work queues up and is delivered when it starts again.
 */
public final class WorkItemEgressWorker implements AutoCloseable {

  private static final Logger LOG = LoggerFactory.getLogger(WorkItemEgressWorker.class);

  private final WorkItemFeeder feeder;
  private final TransactionalOutbox outbox;
  private final OutboxHandler handler;
  private final Duration pollInterval;
  private final CountDownLatch stopped = new CountDownLatch(1);
  private Thread thread;

  /**
   * Creates the worker.
   *
   * @param feeder reads the log
   * @param outbox holds the messages
   * @param handler delivers a message
   * @param pollInterval how long to wait when there was nothing to do
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public WorkItemEgressWorker(
      WorkItemFeeder feeder,
      TransactionalOutbox outbox,
      OutboxHandler handler,
      Duration pollInterval) {
    this.feeder = Objects.requireNonNull(feeder, "feeder");
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.handler = Objects.requireNonNull(handler, "handler");
    this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
  }

  /**
   * Does one pass: feeds one batch of the log, then delivers one batch of messages.
   *
   * @return how much work was found; zero means there was none
   */
  public int runOnce() {
    int fed = feeder.feed();
    DrainResult drained = outbox.drain(handler);
    return fed + drained.total();
  }

  /** Starts working on a background thread. Idempotent. */
  public synchronized void start() {
    if (thread != null) {
      return;
    }
    thread = new Thread(this::run, "work-item-egress");
    thread.setDaemon(true);
    thread.start();
  }

  private void run() {
    while (stopped.getCount() > 0) {
      try {
        if (runOnce() == 0 && pause(pollInterval)) {
          return;
        }
      } catch (RuntimeException e) {
        LOG.warn("work-item egress could not complete a pass; will retry", e);
        if (pause(Duration.ofSeconds(1))) {
          return;
        }
      }
    }
  }

  private boolean pause(Duration duration) {
    try {
      return stopped.await(duration.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return true;
    }
  }

  /** Stops working and waits for the current pass to finish. Idempotent. */
  @Override
  public void close() {
    Thread working;
    synchronized (this) {
      stopped.countDown();
      working = thread;
      thread = null;
    }
    if (working != null) {
      try {
        working.join(10_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
