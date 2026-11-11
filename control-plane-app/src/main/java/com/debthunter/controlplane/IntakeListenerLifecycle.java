package com.debthunter.controlplane;

import com.debthunter.controlplane.ingest.intake.KafkaIntakeListener;
import com.debthunter.controlplane.ingest.receive.RunBundleReceiver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * Starts consuming the intake queue once the application is fully assembled, and stops cleanly when
 * it shuts down. It starts late and stops early so that nothing is half-wired while a bundle is in
 * flight.
 */
final class IntakeListenerLifecycle implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(IntakeListenerLifecycle.class);

  private final ControlPlaneProperties.Intake intake;
  private final RunBundleReceiver receiver;
  private KafkaIntakeListener listener;

  IntakeListenerLifecycle(ControlPlaneProperties.Intake intake, RunBundleReceiver receiver) {
    this.intake = intake;
    this.receiver = receiver;
  }

  @Override
  public synchronized void start() {
    if (listener != null) {
      return;
    }
    listener =
        KafkaIntakeListener.connect(
            intake.bootstrapServers(), intake.consumerGroup(), intake.topic(), receiver);
    listener.start();
    log.info("consuming intake topic={} group={}", intake.topic(), intake.consumerGroup());
  }

  @Override
  public synchronized void stop() {
    if (listener != null) {
      listener.close();
      listener = null;
    }
  }

  @Override
  public synchronized boolean isRunning() {
    return listener != null;
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }
}
