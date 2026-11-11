package com.debthunter.controlplane.ingest.receive;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ingest.intake.IntakeEnvelopes;
import com.debthunter.controlplane.store.quarantine.InMemoryQuarantineRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class StoringQuarantineSinkTest {

  private static final Instant NOW = Instant.parse("2026-03-03T03:03:03Z");

  private final InMemoryQuarantineRepository repository = new InMemoryQuarantineRepository();
  private final StoringQuarantineSink sink =
      new StoringQuarantineSink(repository, Clock.fixed(NOW, ZoneOffset.UTC));

  @Test
  void aSetAsideBundleIsRecordedWithItsReasonAndWhenItWasSetAside() {
    var envelope = IntakeEnvelopes.forRun("run-1", "acme/widgets");

    sink.quarantine(envelope, "run.timestamp is not an ISO-8601 instant");

    assertThat(repository.forRun("run-1"))
        .singleElement()
        .satisfies(
            q -> {
              assertThat(q.reason()).isEqualTo("run.timestamp is not an ISO-8601 instant");
              assertThat(q.correlationId()).isEqualTo(envelope.correlationId().toString());
              assertThat(q.quarantinedAt()).isEqualTo(NOW);
              assertThat(q.repository()).isEqualTo("acme/widgets");
            });
  }

  @Test
  void theBundlesContentIsNeverRecorded() {
    var envelope =
        IntakeEnvelopes.forBundle(
            "{\"schemaVersion\":\"1.0\",\"authors\":[\"alice@example.com\"]}");

    sink.quarantine(envelope, "bad");

    assertThat(repository.forRun(envelope.runId()).toString()).doesNotContain("alice");
  }
}
