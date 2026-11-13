package com.debthunter.controlplane;

import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.context.SmartLifecycle;

/**
 * Whether the read APIs are answering. While stopped, every read endpoint answers 503 and nothing
 * else changes: ingest, the queue and the workers do not consult it.
 */
final class ServeZone implements SmartLifecycle {

  private final AtomicBoolean running = new AtomicBoolean();

  @Override
  public void start() {
    running.set(true);
  }

  @Override
  public void stop() {
    running.set(false);
  }

  @Override
  public boolean isRunning() {
    return running.get();
  }

  @Override
  public int getPhase() {
    return Integer.MAX_VALUE;
  }
}
