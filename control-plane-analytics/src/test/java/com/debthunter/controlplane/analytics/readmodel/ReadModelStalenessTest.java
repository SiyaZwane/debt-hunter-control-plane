package com.debthunter.controlplane.analytics.readmodel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.analytics.MutableClock;
import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** CP-AC-79: a read model that has not been refreshed recently says so. */
class ReadModelStalenessTest {

  private static final Instant T0 = Instant.parse("2026-01-01T12:00:00Z");

  private final InMemoryReadModelRepository readModel = new InMemoryReadModelRepository();
  private final MutableClock clock = new MutableClock(T0);
  private final Staleness staleness = new Staleness(readModel, clock, Duration.ofMinutes(15));

  @Test
  void cpAc79_aReadModelThatHasNeverBeenUpdatedIsStale() {
    Trended<String> result = staleness.wrap("x");

    assertThat(result.stale()).isTrue();
    assertThat(result.lastUpdated()).isNull();
  }

  @Test
  void cpAc79_aRecentlyUpdatedReadModelIsNotStale() {
    readModel.commit(ProjectionBatch.empty(), T0);
    clock.set(T0.plus(Duration.ofMinutes(5)));

    Trended<String> result = staleness.wrap("x");

    assertThat(result.stale()).isFalse();
    assertThat(result.lastUpdated()).isEqualTo(T0);
  }

  @Test
  void cpAc79_onceTheThresholdIsExceededResultsCarryStaleTrue() {
    readModel.commit(ProjectionBatch.empty(), T0);
    clock.set(T0.plus(Duration.ofMinutes(15)).plusSeconds(1));

    assertThat(staleness.wrap("x").stale()).isTrue();
  }

  @Test
  void cpAc79_exactlyAtTheThresholdIsStillFresh() {
    readModel.commit(ProjectionBatch.empty(), T0);
    clock.set(T0.plus(Duration.ofMinutes(15)));

    assertThat(staleness.wrap("x").stale()).isFalse();
  }

  @Test
  void cpAc79_aFreshPassClearsTheFlagAgain() {
    readModel.commit(ProjectionBatch.empty(), T0);
    clock.set(T0.plus(Duration.ofHours(2)));
    assertThat(staleness.wrap("x").stale()).isTrue();

    readModel.commit(ProjectionBatch.empty(), clock.instant());

    assertThat(staleness.wrap("x").stale()).isFalse();
  }

  @Test
  void theWrappedValueIsPassedThroughUntouched() {
    readModel.commit(ProjectionBatch.empty(), T0);

    assertThat(staleness.wrap(java.util.List.of(1, 2, 3)).value()).containsExactly(1, 2, 3);
  }

  @Test
  void aNonPositiveThresholdIsRefused() {
    assertThatThrownBy(() -> new Staleness(readModel, clock, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Staleness(readModel, clock, Duration.ofMinutes(-1)))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
