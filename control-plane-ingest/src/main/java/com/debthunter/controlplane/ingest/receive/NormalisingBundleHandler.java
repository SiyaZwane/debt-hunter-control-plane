package com.debthunter.controlplane.ingest.receive;

import com.debthunter.controlplane.ingest.intake.IntakeEnvelope;
import com.debthunter.controlplane.ingest.normalise.NormalisedRunBundle;
import com.debthunter.controlplane.ingest.normalise.RunNormaliser;
import com.debthunter.controlplane.ingest.normalise.UnnormalisableBundleException;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The first stage after the queue: normalises a bundle and hands it on.
 *
 * <p>The two kinds of failure are told apart deliberately. A bundle that can <em>never</em> be
 * normalised is quarantined and the handler returns normally, because throwing would make the queue
 * listener hold and retry it forever, blocking every bundle behind it in that partition. Anything
 * else (the next stage being down) propagates, so the bundle is retried.
 */
public final class NormalisingBundleHandler implements RunBundleHandler {

  private static final Logger log = LoggerFactory.getLogger(NormalisingBundleHandler.class);

  private final RunNormaliser normaliser;
  private final NormalisedRunSink next;
  private final QuarantineSink quarantine;

  /**
   * Creates a handler.
   *
   * @param normaliser maps bundles onto the internal representation
   * @param next the stage that receives normalised bundles
   * @param quarantine where unnormalisable bundles are set aside
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public NormalisingBundleHandler(
      RunNormaliser normaliser, NormalisedRunSink next, QuarantineSink quarantine) {
    this.normaliser = Objects.requireNonNull(normaliser, "normaliser");
    this.next = Objects.requireNonNull(next, "next");
    this.quarantine = Objects.requireNonNull(quarantine, "quarantine");
  }

  @Override
  public Disposition handle(IntakeEnvelope envelope) {
    NormalisedRunBundle normalised;
    try {
      normalised = normaliser.normalise(envelope);
    } catch (UnnormalisableBundleException e) {
      log.warn(
          "quarantining unnormalisable bundle runId={} correlationId={}: {}",
          envelope.runId(),
          envelope.correlationId(),
          e.getMessage());
      quarantine.quarantine(envelope, e.getMessage());
      return Disposition.SET_ASIDE;
    }
    next.accept(normalised);
    return Disposition.PROCESSED;
  }
}
