package com.debthunter.controlplane;

import com.debthunter.controlplane.store.evidence.EvidenceStorageService;
import com.debthunter.controlplane.store.suppressions.SuppressionRegisterService;
import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * The periodic sweep: moves expired evidence detail to the warm tier and warns the owners of
 * suppressions that are about to lapse.
 *
 * <p>Retention and notification are wall-clock concerns ("it is now past the TTL"), unlike the
 * finding lifecycle, which is judged by commit date. A failure of one task never stops the other or
 * the schedule: it is logged and the next sweep tries again.
 */
final class Housekeeping implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(Housekeeping.class);

  private final EvidenceStorageService evidence;
  private final SuppressionRegisterService suppressions;
  private final Clock clock;
  private final Duration interval;
  private ScheduledExecutorService scheduler;

  Housekeeping(
      EvidenceStorageService evidence,
      SuppressionRegisterService suppressions,
      Clock clock,
      Duration interval) {
    this.evidence = evidence;
    this.suppressions = suppressions;
    this.clock = clock;
    this.interval = interval;
  }

  /** Runs one sweep now. */
  void runOnce() {
    try {
      int moved = evidence.expire(clock.instant());
      if (moved > 0) {
        log.info("moved {} expired evidence snapshot(s) to the warm tier", moved);
      }
    } catch (RuntimeException e) {
      log.error("evidence expiry failed; will retry on the next sweep", e);
    }
    try {
      int warned = suppressions.emitPreExpiryEvents(clock.instant());
      if (warned > 0) {
        log.info("raised {} suppression pre-expiry warning(s)", warned);
      }
    } catch (RuntimeException e) {
      log.error("suppression warnings failed; will retry on the next sweep", e);
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
              Thread thread = new Thread(runnable, "control-plane-housekeeping");
              thread.setDaemon(true);
              return thread;
            });
    scheduler.scheduleWithFixedDelay(
        this::runOnce, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
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
