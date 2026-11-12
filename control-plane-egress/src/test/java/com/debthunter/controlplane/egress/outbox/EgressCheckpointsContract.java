package com.debthunter.controlplane.egress.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** What every checkpoint store must do. */
public abstract class EgressCheckpointsContract {

  protected abstract EgressCheckpoints newCheckpoints();

  @Test
  void aConsumerStartsAtZero() {
    assertThat(newCheckpoints().read("feeder")).isZero();
  }

  @Test
  void aPositionIsRememberedPerConsumer() {
    EgressCheckpoints c = newCheckpoints();

    c.advance("feeder", 10);
    c.advance("digest", 3);

    assertThat(c.read("feeder")).isEqualTo(10);
    assertThat(c.read("digest")).isEqualTo(3);
  }

  @Test
  void aConsumerCanOnlyMoveForward() {
    EgressCheckpoints c = newCheckpoints();
    c.advance("feeder", 10);

    c.advance("feeder", 4);
    assertThat(c.read("feeder")).isEqualTo(10);

    c.advance("feeder", 11);
    assertThat(c.read("feeder")).isEqualTo(11);
  }
}
