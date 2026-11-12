package com.debthunter.controlplane.store.audit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.store.audit.AuditEntry.Kind;
import com.debthunter.controlplane.store.audit.AuditEntry.Outcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AuditRetentionTest {

  private static final Instant NOW = Instant.parse("2026-07-01T12:00:00Z");
  private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

  private static AuditEntry at(Instant when) {
    return new AuditEntry(Kind.ADMIN, when, "c", "a", "t", "", Outcome.ALLOWED);
  }

  @Test
  void aRetentionShorterThanNinetyDaysIsRefused() {
    assertThatThrownBy(
            () ->
                new AuditRetention(
                    new InMemoryAuditTrail(), Duration.ofDays(90).minusSeconds(1), CLOCK))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("90");
  }

  @Test
  void ninetyDaysExactlyIsAllowed() {
    new AuditRetention(new InMemoryAuditTrail(), Duration.ofDays(90), CLOCK);
  }

  @Test
  void purgingRemovesOnlyEntriesOlderThanTheRetention() {
    InMemoryAuditTrail trail = new InMemoryAuditTrail();
    trail.record(at(NOW.minus(Duration.ofDays(400))));
    trail.record(at(NOW.minus(Duration.ofDays(364))));
    trail.record(at(NOW.minus(Duration.ofDays(91))));
    trail.record(at(NOW));

    int removed = new AuditRetention(trail, Duration.ofDays(365), CLOCK).purge();

    assertThat(removed).isEqualTo(1);
    assertThat(trail.read(Kind.ADMIN, Instant.EPOCH, 10)).hasSize(3);
  }

  @Test
  void nothingYoungerThanNinetyDaysIsEverPurged() {
    InMemoryAuditTrail trail = new InMemoryAuditTrail();
    trail.record(at(NOW.minus(Duration.ofDays(89))));
    trail.record(at(NOW.minus(Duration.ofDays(90)).plusSeconds(1)));

    assertThat(new AuditRetention(trail, AuditRetention.MINIMUM, CLOCK).purge()).isZero();
    assertThat(trail.read(Kind.ADMIN, Instant.EPOCH, 10)).hasSize(2);
  }
}
