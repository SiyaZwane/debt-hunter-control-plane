package com.debthunter.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.debthunter.controlplane.store.evidence.EvidenceRepository;
import com.debthunter.controlplane.store.evidence.EvidenceSnapshot;
import com.debthunter.controlplane.store.evidence.EvidenceStorageService;
import com.debthunter.controlplane.store.evidence.EvidenceSummary;
import com.debthunter.controlplane.store.evidence.InMemoryEvidenceRepository;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.suppressions.InMemorySuppressionRepository;
import com.debthunter.controlplane.store.suppressions.SuppressionPreExpiryEvent;
import com.debthunter.controlplane.store.suppressions.SuppressionRegisterService;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class HousekeepingTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant NOW = T0.plus(Duration.ofDays(85));

  private final List<SuppressionPreExpiryEvent> warnings = new ArrayList<>();
  private final SuppressionRegisterService suppressions =
      new SuppressionRegisterService(new InMemorySuppressionRepository(), warnings::add);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

  private static PseudonymisedFinding finding() {
    return new PseudonymisedFinding(
        "f1",
        "rule",
        Category.HOTSPOT,
        Severity.HIGH,
        0.9,
        "a",
        0,
        "m",
        Map.of("changeFrequency", 1.0),
        1.0,
        true,
        "fp",
        List.of());
  }

  @Test
  void aSweepMovesExpiredEvidenceToTheWarmTierAndRaisesDueWarnings() {
    EvidenceStorageService evidence =
        new EvidenceStorageService(new InMemoryEvidenceRepository(), Duration.ofDays(30));
    evidence.store("r1", "acme/widgets", List.of(finding()), T0);
    suppressions.suppress("acme/widgets", "fp-a", "why", T0, T0.plus(Duration.ofDays(90)), 90);

    new Housekeeping(evidence, suppressions, clock, Duration.ofHours(1)).runOnce();

    assertThat(evidence.hotFor("r1")).isEmpty();
    assertThat(evidence.warmSummaryFor("acme/widgets")).hasSize(1);
    assertThat(warnings).hasSize(1);
  }

  @Test
  void aFailureExpiringEvidenceDoesNotStopTheSuppressionWarnings() {
    EvidenceRepository broken =
        new StubEvidenceRepository(
            () -> {
              throw new IllegalStateException("database down");
            });
    suppressions.suppress("acme/widgets", "fp-a", "why", T0, T0.plus(Duration.ofDays(90)), 90);

    new Housekeeping(
            new EvidenceStorageService(broken, Duration.ofDays(30)),
            suppressions,
            clock,
            Duration.ofHours(1))
        .runOnce();

    assertThat(warnings).hasSize(1);
  }

  @Test
  void aFailureWarningAboutSuppressionsDoesNotStopEvidenceExpiry() {
    EvidenceStorageService evidence =
        new EvidenceStorageService(new InMemoryEvidenceRepository(), Duration.ofDays(30));
    evidence.store("r1", "acme/widgets", List.of(finding()), T0);
    SuppressionRegisterService broken =
        new SuppressionRegisterService(
            new InMemorySuppressionRepository(),
            event -> {
              throw new IllegalStateException("notifier down");
            });
    broken.suppress("acme/widgets", "fp-a", "why", T0, T0.plus(Duration.ofDays(90)), 90);

    new Housekeeping(evidence, broken, clock, Duration.ofHours(1)).runOnce();

    assertThat(evidence.hotFor("r1")).isEmpty();
  }

  @Test
  void startedItSweepsOnItsScheduleAndStoppedItStops() {
    int[] sweeps = {0};
    EvidenceRepository counting =
        new StubEvidenceRepository(
            () -> {
              sweeps[0]++;
              return 0;
            });
    Housekeeping housekeeping =
        new Housekeeping(
            new EvidenceStorageService(counting, Duration.ofDays(30)),
            suppressions,
            clock,
            Duration.ofMillis(20));

    assertThat(housekeeping.isRunning()).isFalse();
    housekeeping.start();
    housekeeping.start(); // idempotent
    await().atMost(Duration.ofSeconds(10)).until(() -> sweeps[0] >= 3);
    assertThat(housekeeping.isRunning()).isTrue();

    housekeeping.stop();
    int atStop = sweeps[0];
    assertThat(housekeeping.isRunning()).isFalse();
    try {
      Thread.sleep(150);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    assertThat(sweeps[0]).isLessThanOrEqualTo(atStop + 1);
  }

  /** An evidence repository whose expiry sweep is whatever the test says it is. */
  private record StubEvidenceRepository(java.util.function.IntSupplier sweep)
      implements EvidenceRepository {

    @Override
    public void saveHot(EvidenceSnapshot snapshot) {}

    @Override
    public List<EvidenceSnapshot> hotForRun(String runId) {
      return List.of();
    }

    @Override
    public int moveExpiredToWarm(Instant asOf) {
      return sweep.getAsInt();
    }

    @Override
    public List<EvidenceSummary> warmSummaries(String repository) {
      return List.of();
    }
  }
}
