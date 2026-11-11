package com.debthunter.controlplane.store.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

abstract class EvidenceRepositoryContract {

  static final Instant T0 = Instant.parse("2026-01-01T10:00:00Z");

  protected abstract EvidenceRepository newRepository();

  private EvidenceRepository repository;

  @BeforeEach
  void create() {
    repository = newRepository();
  }

  static EvidenceSnapshot snapshot(
      String run, String rule, String severity, Instant at, long ttlDays) {
    return EvidenceSnapshot.restore(
        run + rule + at,
        run,
        "acme/widgets",
        "f-" + rule,
        "fp-" + rule,
        rule,
        severity,
        Map.of("changeFrequency", 12.0),
        at,
        at.plus(Duration.ofDays(ttlDays)));
  }

  @Test
  void aHotSnapshotReadsBackWithItsEvidence() {
    repository.saveHot(snapshot("r1", "rule-a", "HIGH", T0, 30));

    assertThat(repository.hotForRun("r1"))
        .singleElement()
        .satisfies(s -> assertThat(s.evidence()).containsEntry("changeFrequency", 12.0));
    assertThat(repository.hotForRun("other")).isEmpty();
  }

  @Test
  void expiredSnapshotsLeaveTheHotTierAndAreCountedInTheWarmTier() {
    repository.saveHot(snapshot("r1", "rule-a", "HIGH", T0, 1));
    repository.saveHot(snapshot("r1", "rule-b", "LOW", T0, 30));

    int moved = repository.moveExpiredToWarm(T0.plus(Duration.ofDays(2)));

    assertThat(moved).isEqualTo(1);
    assertThat(repository.hotForRun("r1"))
        .extracting(EvidenceSnapshot::ruleId)
        .containsExactly("rule-b");
    assertThat(repository.warmSummaries("acme/widgets"))
        .containsExactly(
            new EvidenceSummary("acme/widgets", "rule-a", "HIGH", LocalDate.of(2026, 1, 1), 1));
  }

  @Test
  void warmCountsAccumulateAcrossExpirySweeps() {
    repository.saveHot(snapshot("r1", "rule-a", "HIGH", T0, 1));
    repository.moveExpiredToWarm(T0.plus(Duration.ofDays(2)));
    repository.saveHot(snapshot("r2", "rule-a", "HIGH", T0.plusSeconds(60), 1));
    repository.moveExpiredToWarm(T0.plus(Duration.ofDays(3)));

    assertThat(repository.warmSummaries("acme/widgets"))
        .singleElement()
        .satisfies(s -> assertThat(s.findingCount()).isEqualTo(2));
  }

  @Test
  void aSecondSweepWithNothingNewMovesNothingAndCountsNothingTwice() {
    repository.saveHot(snapshot("r1", "rule-a", "HIGH", T0, 1));
    repository.moveExpiredToWarm(T0.plus(Duration.ofDays(2)));

    assertThat(repository.moveExpiredToWarm(T0.plus(Duration.ofDays(2)))).isZero();
    assertThat(repository.warmSummaries("acme/widgets").get(0).findingCount()).isEqualTo(1);
  }

  @Test
  void evidenceNotYetDueStaysHot() {
    repository.saveHot(snapshot("r1", "rule-a", "HIGH", T0, 30));

    assertThat(repository.moveExpiredToWarm(T0.plus(Duration.ofDays(1)))).isZero();
    assertThat(repository.hotForRun("r1")).hasSize(1);
    assertThat(repository.warmSummaries("acme/widgets")).isEmpty();
  }
}
