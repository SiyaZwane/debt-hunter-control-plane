package com.debthunter.controlplane.ingest.receive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.ingest.intake.IntakeEnvelope;
import com.debthunter.controlplane.ingest.intake.IntakeEnvelopes;
import com.debthunter.controlplane.ingest.normalise.NormalisedRunBundle;
import com.debthunter.controlplane.ingest.normalise.RunNormaliser;
import com.debthunter.controlplane.testkit.RunBundleFixtures;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class NormalisingBundleHandlerTest {

  private final List<NormalisedRunBundle> accepted = new ArrayList<>();
  private final List<String> quarantined = new ArrayList<>();

  private NormalisingBundleHandler handler(NormalisedRunSink next, QuarantineSink quarantine) {
    return new NormalisingBundleHandler(new RunNormaliser(), next, quarantine);
  }

  private NormalisingBundleHandler recordingHandler() {
    return handler(accepted::add, (envelope, reason) -> quarantined.add(reason));
  }

  @Test
  void aNormalisableBundleIsPassedOnNormalised() {
    recordingHandler()
        .handle(IntakeEnvelopes.forBundle(RunBundleFixtures.validV0("run-5", "acme/widgets")));

    assertThat(accepted)
        .singleElement()
        .satisfies(b -> assertThat(b.scan().run().id()).isEqualTo("run-5"));
    assertThat(quarantined).isEmpty();
  }

  @Test
  void aBundleThatCanNeverBeNormalisedIsQuarantinedAndTheHandlerReturnsNormally() {
    IntakeEnvelope envelope = IntakeEnvelopes.forBundle(RunBundleFixtures.notJson());

    var disposition = recordingHandler().handle(envelope);

    assertThat(disposition).isEqualTo(RunBundleHandler.Disposition.SET_ASIDE);
    assertThat(accepted).isEmpty();
    assertThat(quarantined).singleElement().asString().contains("not valid JSON");
  }

  @Test
  void aFailureOfTheNextStagePropagatesSoTheBundleIsRetried() {
    NormalisingBundleHandler handler =
        handler(
            bundle -> {
              throw new IllegalStateException("store is down");
            },
            (envelope, reason) -> quarantined.add(reason));
    IntakeEnvelope envelope = IntakeEnvelopes.forBundle(RunBundleFixtures.validV1());

    assertThatThrownBy(() -> handler.handle(envelope))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("store is down");
    assertThat(quarantined).isEmpty();
  }

  @Test
  void aFailureToQuarantinePropagatesSoTheBundleIsNotLost() {
    NormalisingBundleHandler handler =
        handler(
            accepted::add,
            (envelope, reason) -> {
              throw new IllegalStateException("quarantine store is down");
            });
    IntakeEnvelope envelope = IntakeEnvelopes.forBundle(RunBundleFixtures.notJson());

    assertThatThrownBy(() -> handler.handle(envelope)).isInstanceOf(IllegalStateException.class);
  }
}
