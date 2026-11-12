package com.debthunter.controlplane.store.queue;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class QueuePolicyTest {

  @Test
  void nonsensePoliciesAreRefused() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> new QueuePolicy(true, 0, Duration.ofSeconds(1), Duration.ZERO, Duration.ZERO));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new QueuePolicy(true, 1, Duration.ZERO, Duration.ZERO, Duration.ZERO));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new QueuePolicy(
                    true, 1, Duration.ofSeconds(1), Duration.ofSeconds(5), Duration.ofSeconds(1)));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new QueuePolicy(
                    true, 1, Duration.ofSeconds(1), Duration.ofSeconds(-1), Duration.ofSeconds(1)));
  }
}
