package com.debthunter.controlplane.governance.publication;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SupersededRetentionTest {

  @Test
  void aBundleIsKeptForTwoMajorCyclesAfterItWasSuperseded() {
    assertThat(SupersededRetention.mustRetain(3, 3)).isTrue();
    assertThat(SupersededRetention.mustRetain(3, 4)).isTrue();
    assertThat(SupersededRetention.mustRetain(3, 5)).isTrue();
  }

  @Test
  void aBundleMayBeRemovedOnlyAfterTheSecondMajorCycleHasPassed() {
    assertThat(SupersededRetention.mustRetain(3, 6)).isFalse();
  }
}
