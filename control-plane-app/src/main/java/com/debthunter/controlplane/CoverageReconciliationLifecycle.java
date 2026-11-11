package com.debthunter.controlplane;

import com.debthunter.controlplane.analytics.coverage.CoverageReport;
import com.debthunter.controlplane.analytics.coverage.CoverageState;
import com.debthunter.controlplane.analytics.coverage.CoverageTracker;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Reconciles the registry against the source-control estate on a schedule, on its own thread.
 *
 * <p>Like the projector it is isolated from ingest, and every failure is logged and absorbed so the
 * schedule continues. A repository falling silent is exactly the kind of thing that must not go
 * unnoticed, so a reconciliation that finds uncovered repositories says so at WARN.
 */
final class CoverageReconciliationLifecycle implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(CoverageReconciliationLifecycle.class);

  private final CoverageTracker tracker;
  private final Duration initialDelay;
  private final Duration interval;
  private ScheduledExecutorService scheduler;

  CoverageReconciliationLifecycle(
      CoverageTracker tracker, Duration initialDelay, Duration interval) {
    this.tracker = tracker;
    this.initialDelay = initialDelay;
    this.interval = interval;
  }

  /** Reconciles now; failures are absorbed. */
  void runOnce() {
    try {
      CoverageReport report = tracker.reconcile();
      var counts = report.countsByState();
      String summary =
          "coverage reconciled: reporting={} silent={} neverOnboarded={} degraded={} versionDrift={}";
      Object[] numbers = {
        counts.get(CoverageState.REPORTING),
        counts.get(CoverageState.SILENT),
        counts.get(CoverageState.NEVER_ONBOARDED),
        counts.get(CoverageState.DEGRADED_REPORTING),
        counts.get(CoverageState.VERSION_DRIFT)
      };
      if (report.uncovered().isEmpty()) {
        log.info(summary, numbers);
      } else {
        log.warn(summary, numbers);
      }
      report.warnings().forEach(w -> log.warn("coverage: {}", w));
    } catch (RuntimeException e) {
      log.error("coverage reconciliation failed; will retry on the next schedule", e);
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
              Thread thread = new Thread(runnable, "control-plane-coverage");
              thread.setDaemon(true);
              return thread;
            });
    scheduler.scheduleWithFixedDelay(
        this::runOnce, initialDelay.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
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
