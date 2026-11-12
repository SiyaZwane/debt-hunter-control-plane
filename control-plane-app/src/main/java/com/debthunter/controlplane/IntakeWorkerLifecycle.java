package com.debthunter.controlplane;

import com.debthunter.controlplane.ingest.intake.DurableIntakeWorker;
import com.debthunter.controlplane.ingest.intake.IntakeEnvelopeCodec;
import com.debthunter.controlplane.ingest.receive.RunBundleReceiver;
import com.debthunter.controlplane.store.queue.PayloadCipher;
import com.debthunter.controlplane.store.queue.WorkQueue;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Starts working the intake queue once the application is fully assembled, and stops cleanly when
 * it shuts down. It starts late and stops early so that nothing is half-wired while a bundle is in
 * flight.
 */
final class IntakeWorkerLifecycle implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(IntakeWorkerLifecycle.class);

  private final ControlPlaneProperties.Intake intake;
  private final WorkQueue queue;
  private final PayloadCipher cipher;
  private final RunBundleReceiver receiver;
  private DurableIntakeWorker worker;

  IntakeWorkerLifecycle(
      ControlPlaneProperties.Intake intake,
      WorkQueue queue,
      PayloadCipher cipher,
      RunBundleReceiver receiver) {
    this.intake = intake;
    this.queue = queue;
    this.cipher = cipher;
    this.receiver = receiver;
  }

  @Override
  public synchronized void start() {
    if (worker != null) {
      return;
    }
    String workerId = "worker-" + UUID.randomUUID();
    worker =
        new DurableIntakeWorker(
            queue,
            cipher,
            new IntakeEnvelopeCodec(),
            receiver,
            workerId,
            intake.batchSize(),
            intake.pollInterval());
    worker.start();
    log.info("working the intake queue as {}", workerId);
  }

  @Override
  public synchronized void stop() {
    if (worker != null) {
      worker.close();
      worker = null;
    }
  }

  @Override
  public synchronized boolean isRunning() {
    return worker != null;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }
}
