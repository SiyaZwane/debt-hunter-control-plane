package com.debthunter.controlplane;

import com.debthunter.controlplane.analytics.projection.MetricProjector;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Runs the metric projector on its own thread, on a schedule.
 *
 * <p>It is deliberately isolated from ingest: its own single daemon thread, no shared locks, and
 * every failure is logged and absorbed so the schedule continues. A failing, slow or stopped
 * projector makes the read models stale and does nothing else.
 */
final class ProjectorLifecycle implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(ProjectorLifecycle.class);

  private final MetricProjector projector;
  private final Duration interval;
  private ScheduledExecutorService scheduler;

  ProjectorLifecycle(MetricProjector projector, Duration interval) {
    this.projector = projector;
    this.interval = interval;
  }

  /** Runs one catch-up now; failures are absorbed. */
  void runOnce() {
    try {
      int projected = projector.project();
      if (projected > 0) {
        log.info("projected {} item(s) into the analytics read models", projected);
      }
    } catch (RuntimeException e) {
      log.error("analytics projection failed; the read models will go stale until it recovers", e);
    }
  }

  @Override
  public synchronized void start() {
    if (scheduler != null) {
      return;
    }
    scheduler =
        Executors.newSingleThreadScheduledExecutor(
            runnable -> {
              Thread thread = new Thread(runnable, "control-plane-projector");
              thread.setDaemon(true);
              return thread;
            });
    scheduler.scheduleWithFixedDelay(this::runOnce, 0, interval.toMillis(), TimeUnit.MILLISECONDS);
  }

  @Override
  public synchronized void stop() {
    if (scheduler != null) {
      scheduler.shutdownNow();
      scheduler = null;
    }
  }

  @Override
  public synchronized boolean isRunning() {
    return scheduler != null;
  }
}
