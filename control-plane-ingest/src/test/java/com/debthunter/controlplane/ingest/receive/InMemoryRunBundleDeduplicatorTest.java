package com.debthunter.controlplane.ingest.receive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class InMemoryRunBundleDeduplicatorTest {

  @Test
  void aRunIsNotProcessedUntilItIsMarked() {
    RunBundleDeduplicator deduplicator = new InMemoryRunBundleDeduplicator(10);

    assertThat(deduplicator.alreadyProcessed("run-1")).isFalse();
    deduplicator.markProcessed("run-1");
    assertThat(deduplicator.alreadyProcessed("run-1")).isTrue();
  }

  @Test
  void theLeastRecentlySeenRunIsForgottenOnceCapacityIsExceeded() {
    RunBundleDeduplicator deduplicator = new InMemoryRunBundleDeduplicator(2);

    deduplicator.markProcessed("run-1");
    deduplicator.markProcessed("run-2");
    deduplicator.markProcessed("run-3");

    assertThat(deduplicator.alreadyProcessed("run-1")).isFalse();
    assertThat(deduplicator.alreadyProcessed("run-2")).isTrue();
    assertThat(deduplicator.alreadyProcessed("run-3")).isTrue();
  }

  @Test
  void checkingARunKeepsItFromBeingTheNextEvicted() {
    RunBundleDeduplicator deduplicator = new InMemoryRunBundleDeduplicator(2);
    deduplicator.markProcessed("run-1");
    deduplicator.markProcessed("run-2");

    deduplicator.alreadyProcessed("run-1");
    deduplicator.markProcessed("run-3");

    assertThat(deduplicator.alreadyProcessed("run-1")).isTrue();
    assertThat(deduplicator.alreadyProcessed("run-2")).isFalse();
  }

  @Test
  void aNonPositiveCapacityIsRejected() {
    assertThatThrownBy(() -> new InMemoryRunBundleDeduplicator(0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
