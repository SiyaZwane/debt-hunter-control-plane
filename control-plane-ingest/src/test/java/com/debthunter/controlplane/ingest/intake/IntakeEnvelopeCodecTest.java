package com.debthunter.controlplane.ingest.intake;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class IntakeEnvelopeCodecTest {

  private final IntakeEnvelopeCodec codec = new IntakeEnvelopeCodec();

  @Test
  void anEnvelopeSurvivesARoundTripUnchanged() {
    IntakeEnvelope original = IntakeEnvelopes.forRun("run-1");

    IntakeEnvelope decoded = codec.decode(codec.encode(original));

    assertThat(decoded).isEqualTo(original);
  }

  @Test
  void theReceivedTimeIsWrittenAsAnIsoStringNotAnEpochNumber() {
    String json = codec.encode(IntakeEnvelopes.forRun("run-1"));

    assertThat(json).contains("\"receivedAt\":\"2026-01-01T00:00:00Z\"");
  }

  @Test
  void textThatIsNotAnEnvelopeIsReportedAsUndecodable() {
    assertThatThrownBy(() -> codec.decode("{ not json"))
        .isInstanceOf(IntakeEnvelopeCodec.UndecodableEnvelopeException.class);
  }

  @Test
  void jsonMissingRequiredFieldsIsReportedAsUndecodable() {
    assertThatThrownBy(() -> codec.decode("{\"runId\":\"run-1\"}"))
        .isInstanceOf(IntakeEnvelopeCodec.UndecodableEnvelopeException.class);
  }
}
