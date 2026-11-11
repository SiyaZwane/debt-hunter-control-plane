package com.debthunter.controlplane.ingest.receive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.ingest.intake.IntakeEnvelope;
import com.debthunter.controlplane.ingest.intake.IntakeEnvelopes;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class RunBundleReceiverTest {

  private final List<IntakeEnvelope> handled = new ArrayList<>();
  private final RunBundleDeduplicator deduplicator = new InMemoryRunBundleDeduplicator(100);

  @Test
  void aNewBundleIsDispatchedToTheHandler() {
    RunBundleReceiver receiver =
        new RunBundleReceiver(deduplicator, Handlers.processing(handled::add));
    IntakeEnvelope envelope = IntakeEnvelopes.forRun("run-1");

    RunBundleReceiver.Outcome outcome = receiver.receive(envelope);

    assertThat(outcome).isEqualTo(RunBundleReceiver.Outcome.PROCESSED);
    assertThat(handled).containsExactly(envelope);
  }

  @Test
  void aRedeliveredBundleIsDiscardedWithoutReprocessing() {
    RunBundleReceiver receiver =
        new RunBundleReceiver(deduplicator, Handlers.processing(handled::add));

    receiver.receive(IntakeEnvelopes.forRun("run-1"));
    RunBundleReceiver.Outcome second = receiver.receive(IntakeEnvelopes.forRun("run-1"));

    assertThat(second).isEqualTo(RunBundleReceiver.Outcome.DUPLICATE);
    assertThat(handled).hasSize(1);
  }

  @Test
  void aFailedBundleIsNotMarkedProcessedSoItsRedeliveryIsProcessed() {
    boolean[] fail = {true};
    RunBundleReceiver receiver =
        new RunBundleReceiver(
            deduplicator,
            envelope -> {
              if (fail[0]) {
                throw new IllegalStateException("downstream unavailable");
              }
              handled.add(envelope);
              return RunBundleHandler.Disposition.PROCESSED;
            });
    IntakeEnvelope envelope = IntakeEnvelopes.forRun("run-1");

    assertThatThrownBy(() -> receiver.receive(envelope)).isInstanceOf(IllegalStateException.class);
    assertThat(deduplicator.alreadyProcessed("run-1")).isFalse();

    fail[0] = false;
    assertThat(receiver.receive(envelope)).isEqualTo(RunBundleReceiver.Outcome.PROCESSED);
    assertThat(handled).containsExactly(envelope);
  }

  @Test
  void differentRunsAreProcessedIndependently() {
    RunBundleReceiver receiver =
        new RunBundleReceiver(deduplicator, Handlers.processing(handled::add));

    receiver.receive(IntakeEnvelopes.forRun("run-1"));
    receiver.receive(IntakeEnvelopes.forRun("run-2"));

    assertThat(handled).extracting(IntakeEnvelope::runId).containsExactly("run-1", "run-2");
  }

  @Test
  void aBundleSetAsideIsNotMarkedProcessedSoACorrectedOneUnderTheSameRunIdIsNotADuplicate() {
    boolean[] corrected = {false};
    RunBundleReceiver receiver =
        new RunBundleReceiver(
            deduplicator,
            envelope ->
                corrected[0]
                    ? RunBundleHandler.Disposition.PROCESSED
                    : RunBundleHandler.Disposition.SET_ASIDE);
    IntakeEnvelope envelope = IntakeEnvelopes.forRun("run-1");

    assertThat(receiver.receive(envelope)).isEqualTo(RunBundleReceiver.Outcome.SET_ASIDE);
    assertThat(deduplicator.alreadyProcessed("run-1")).isFalse();

    corrected[0] = true;
    assertThat(receiver.receive(envelope)).isEqualTo(RunBundleReceiver.Outcome.PROCESSED);
  }
}
