package com.debthunter.controlplane.ingest.receive;

import com.debthunter.controlplane.ingest.intake.IntakeEnvelope;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Takes bundles off the intake queue and dispatches each to the next stage of ingest exactly once,
 * however many times the queue delivers it.
 *
 * <p>A run is marked processed only after the handler succeeds. If the handler throws, the run is
 * left unmarked so the redelivery is processed — which is why the queue's at-least-once delivery is
 * safe rather than lossy.
 */
public final class RunBundleReceiver {

  /** What became of a received bundle. */
  public enum Outcome {
    /** The handler processed it. */
    PROCESSED,
    /** It had already been processed; the redelivery was discarded. */
    DUPLICATE
  }

  private static final Logger log = LoggerFactory.getLogger(RunBundleReceiver.class);

  private final RunBundleDeduplicator deduplicator;
  private final RunBundleHandler handler;

  /**
   * Creates a receiver.
   *
   * @param deduplicator remembers which runs are done
   * @param handler the next stage of ingest
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public RunBundleReceiver(RunBundleDeduplicator deduplicator, RunBundleHandler handler) {
    this.deduplicator = Objects.requireNonNull(deduplicator, "deduplicator");
    this.handler = Objects.requireNonNull(handler, "handler");
  }

  /**
   * Receives one bundle.
   *
   * @param envelope the bundle taken from the queue
   * @return whether it was processed or discarded as a duplicate
   * @throws RuntimeException if the handler failed; the bundle is then not marked processed
   */
  public Outcome receive(IntakeEnvelope envelope) {
    if (deduplicator.alreadyProcessed(envelope.runId())) {
      log.info(
          "discarding duplicate delivery runId={} correlationId={}",
          envelope.runId(),
          envelope.correlationId());
      return Outcome.DUPLICATE;
    }
    handler.handle(envelope);
    deduplicator.markProcessed(envelope.runId());
    return Outcome.PROCESSED;
  }
}
