package com.debthunter.controlplane;

import java.util.Objects;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;

/**
 * An optional background zone (work-item egress, digests) that can be stopped and started without
 * restarting the application. Stopping it stops only its worker: whatever it was going to deliver
 * stays queued in the system of record, and is delivered when it starts again. Nothing else waits
 * on it.
 */
final class WorkerZone implements SmartLifecycle {

  private static final Logger log = LoggerFactory.getLogger(WorkerZone.class);

  /** A running worker. */
  interface Worker extends AutoCloseable {
    /** Starts working. */
    void start();

    @Override
    void close();
  }

  private final String name;
  private final Supplier<Worker> factory;
  private Worker worker;

  WorkerZone(String name, Supplier<Worker> factory) {
    this.name = Objects.requireNonNull(name, "name");
    this.factory = Objects.requireNonNull(factory, "factory");
  }

  @Override
  public synchronized void start() {
    if (worker != null) {
      return;
    }
    Worker created = factory.get();
    created.start();
    worker = created;
    log.info("zone {} started", name);
  }

  @Override
  public synchronized void stop() {
    if (worker != null) {
      worker.close();
      worker = null;
      log.info("zone {} stopped", name);
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
