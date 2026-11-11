package com.debthunter.controlplane.store.suppressions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SuppressionRegisterServiceTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

  private final List<SuppressionPreExpiryEvent> events = new ArrayList<>();
  private final SuppressionRegisterService service =
      new SuppressionRegisterService(new InMemorySuppressionRepository(), events::add);

  private static Instant day(int n) {
    return T0.plus(Duration.ofDays(n));
  }

  @Test
  void aSuppressedFindingIsSuppressedUntilItExpires() {
    service.suppress("acme/widgets", "fp-a", "accepted risk", T0, day(30), 90);

    assertThat(service.isSuppressed("acme/widgets", "fp-a", day(10))).isTrue();
    assertThat(service.isSuppressed("acme/widgets", "fp-a", day(30))).isFalse();
    assertThat(service.isSuppressed("acme/widgets", "fp-a", day(31))).isFalse();
  }

  @Test
  void aFindingThatWasNeverSuppressedIsNot() {
    assertThat(service.isSuppressed("acme/widgets", "fp-z", day(1))).isFalse();
    assertThat(service.find("acme/widgets", "fp-z")).isEmpty();
  }

  @Test
  void revokingEndsTheSuppression() {
    Suppression s = service.suppress("acme/widgets", "fp-a", "accepted risk", T0, day(30), 90);

    assertThat(service.revoke(s.id())).isTrue();
    assertThat(service.isSuppressed("acme/widgets", "fp-a", day(1))).isFalse();
  }

  @Test
  void aSuppressionWithoutAReasonIsRefused() {
    assertThatThrownBy(() -> service.suppress("acme/widgets", "fp-a", "  ", T0, day(30), 90))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void aSuppressionMayNotOutliveItsPolicyCap() {
    assertThatThrownBy(() -> service.suppress("acme/widgets", "fp-a", "why", T0, day(91), 90))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> service.suppress("acme/widgets", "fp-a", "why", T0, T0, 90))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void cpAc26_theOwnerIsWarnedSevenDaysBeforeTheMaximumLifeEnds() {
    service.suppress("acme/widgets", "fp-a", "why", T0, day(90), 90);

    assertThat(service.emitPreExpiryEvents(day(82))).isZero();
    assertThat(service.emitPreExpiryEvents(day(83))).isEqualTo(1);

    assertThat(events)
        .singleElement()
        .satisfies(
            e -> {
              assertThat(e.fingerprint()).isEqualTo("fp-a");
              assertThat(e.expiresAt()).isEqualTo(day(90));
              assertThat(e.raisedAsOf()).isEqualTo(day(83));
            });
  }

  @Test
  void cpAc26_aShorterSuppressionIsWarnedSevenDaysBeforeItsOwnExpiryNotAfterItHasLapsed() {
    service.suppress("acme/widgets", "fp-a", "why", T0, day(30), 90);

    assertThat(service.emitPreExpiryEvents(day(22))).isZero();
    assertThat(service.emitPreExpiryEvents(day(23))).isEqualTo(1);
  }

  @Test
  void cpAc26_eachSuppressionIsWarnedOnceNoMatterHowOftenTheSweepRuns() {
    service.suppress("acme/widgets", "fp-a", "why", T0, day(90), 90);

    service.emitPreExpiryEvents(day(84));
    service.emitPreExpiryEvents(day(85));
    service.emitPreExpiryEvents(day(86));

    assertThat(events).hasSize(1);
  }

  @Test
  void cpAc26_noWarningIsRaisedForASuppressionThatHasAlreadyLapsedAsOfTheDate() {
    service.suppress("acme/widgets", "fp-a", "why", T0, day(30), 90);

    assertThat(service.emitPreExpiryEvents(day(40))).isZero();
    assertThat(events).isEmpty();
  }

  @Test
  void cpAc26_aSuppressionWithAShortCapIsWarnedImmediately() {
    service.suppress("acme/widgets", "fp-a", "why", T0, day(5), 5);

    assertThat(service.emitPreExpiryEvents(T0)).isEqualTo(1);
  }

  @Test
  void aFailedPublishIsNotMarkedSoTheNextSweepRaisesItAgain() {
    boolean[] up = {false};
    List<SuppressionPreExpiryEvent> delivered = new ArrayList<>();
    SuppressionRegisterService flaky =
        new SuppressionRegisterService(
            new InMemorySuppressionRepository(),
            event -> {
              if (!up[0]) {
                throw new IllegalStateException("notifier down");
              }
              delivered.add(event);
            });
    flaky.suppress("acme/widgets", "fp-a", "why", T0, day(90), 90);

    assertThatThrownBy(() -> flaky.emitPreExpiryEvents(day(84)))
        .isInstanceOf(IllegalStateException.class);
    up[0] = true;

    assertThat(flaky.emitPreExpiryEvents(day(85))).isEqualTo(1);
    assertThat(delivered).hasSize(1);
  }
}
