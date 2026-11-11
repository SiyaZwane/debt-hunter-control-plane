package com.debthunter.controlplane.ingest.receive;

import com.debthunter.controlplane.ingest.intake.IntakeEnvelope;
import com.debthunter.controlplane.store.quarantine.QuarantineRepository;
import com.debthunter.controlplane.store.quarantine.QuarantinedBundle;
import java.time.Clock;
import java.util.Objects;

/**
 * Records a bundle that can never be processed. Only metadata and the reason are kept: the bundle
 * itself still carries raw contributor identity and stays on the intake topic.
 */
public final class StoringQuarantineSink implements QuarantineSink {

  private final QuarantineRepository quarantine;
  private final Clock clock;

  /**
   * Creates the sink.
   *
   * @param quarantine where bundles set aside are recorded
   * @param clock the source of the time a bundle was set aside
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public StoringQuarantineSink(QuarantineRepository quarantine, Clock clock) {
    this.quarantine = Objects.requireNonNull(quarantine, "quarantine");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public void quarantine(IntakeEnvelope envelope, String reason) {
    quarantine.save(
        new QuarantinedBundle(
            envelope.correlationId().toString(),
            envelope.runId(),
            envelope.repository(),
            envelope.workloadIdentity(),
            envelope.schemaVersion(),
            reason,
            envelope.receivedAt(),
            clock.instant()));
  }
}
