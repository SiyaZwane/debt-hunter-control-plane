package com.debthunter.controlplane.egress.digest;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** What every digest ledger must do. */
public abstract class DigestLedgerContract {

  private static final Instant D1 = Instant.parse("2026-07-01T00:00:00Z");
  private static final Instant D2 = D1.plus(Duration.ofDays(1));
  private static final Instant D3 = D2.plus(Duration.ofDays(1));
  private static final Instant NOW = Instant.parse("2026-07-05T12:00:00Z");

  protected abstract DigestLedger newLedger();

  private DigestLedger ledger;

  @BeforeEach
  void setUp() {
    ledger = newLedger();
  }

  @Test
  void theFirstPeriodEverSeenIsAnEmptyBaseline() {
    DigestLedger.Window w = ledger.window(D1, D2, 40);

    assertThat(w.baseline()).isTrue();
    assertThat(w.fromSeq()).isEqualTo(40);
    assertThat(w.toSeq()).isEqualTo(40);
  }

  @Test
  void aLaterPeriodRunsFromWhereTheLastOneStopped() {
    ledger.window(D1, D2, 40);

    DigestLedger.Window w = ledger.window(D2, D3, 55);

    assertThat(w.baseline()).isFalse();
    assertThat(w.fromSeq()).isEqualTo(40);
    assertThat(w.toSeq()).isEqualTo(55);
  }

  @Test
  void aPeriodAfterAGapRunsFromTheLastRecordedOne() {
    ledger.window(D1, D2, 40);

    DigestLedger.Window w = ledger.window(D3, D3.plus(Duration.ofDays(1)), 90);

    assertThat(w.fromSeq()).as("the missed period is covered too").isEqualTo(40);
  }

  @Test
  void aPeriodsWindowIsFixedTheFirstTimeAndNeverMoves() {
    DigestLedger.Window first = ledger.window(D1, D2, 40);
    ledger.window(D2, D3, 55);

    assertThat(ledger.window(D1, D2, 99)).isEqualTo(first);
    assertThat(ledger.window(D2, D3, 99).toSeq()).isEqualTo(55);
  }

  @Test
  void aDigestIsRecordedOnceAndReadsBackUnqueued() {
    assertThat(ledger.record("team-a", D1, "{\"x\":1}", NOW)).isTrue();
    assertThat(ledger.record("team-a", D1, "{\"x\":2}", NOW)).isFalse();

    DigestLedger.Entry e = ledger.find("team-a", D1).orElseThrow();
    assertThat(e.payload()).isEqualTo("{\"x\":1}");
    assertThat(e.queuedAt()).isNull();
    assertThat(e.sentAt()).isNull();
    assertThat(ledger.unqueued()).extracting(DigestLedger.Entry::owner).containsExactly("team-a");
  }

  @Test
  void nothingToSayIsRecordedAsAlreadyQueuedAndSent() {
    ledger.record("team-a", D1, null, NOW);

    DigestLedger.Entry e = ledger.find("team-a", D1).orElseThrow();
    assertThat(e.payload()).isNull();
    assertThat(e.queuedAt()).isEqualTo(NOW);
    assertThat(e.sentAt()).isEqualTo(NOW);
    assertThat(ledger.unqueued()).isEmpty();
  }

  @Test
  void queuedAndSentAreRememberedPerSquadAndPeriod() {
    ledger.record("team-a", D1, "a1", NOW);
    ledger.record("team-b", D1, "b1", NOW);
    ledger.record("team-a", D2, "a2", NOW);

    ledger.markQueued("team-a", D1, NOW.plusSeconds(1));
    ledger.markSent("team-a", D1, NOW.plusSeconds(2));

    DigestLedger.Entry done = ledger.find("team-a", D1).orElseThrow();
    assertThat(done.queuedAt()).isEqualTo(NOW.plusSeconds(1));
    assertThat(done.sentAt()).isEqualTo(NOW.plusSeconds(2));
    assertThat(ledger.unqueued())
        .extracting(e -> e.owner() + "@" + e.periodStart())
        .containsExactlyInAnyOrder("team-b@" + D1, "team-a@" + D2);
    assertThat(ledger.find("team-a", D3)).isEmpty();
  }
}
