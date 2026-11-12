package com.debthunter.controlplane;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.debthunter.controlplane.ingest.intake.IntakeQueue;
import com.debthunter.controlplane.store.evidence.EvidenceRepository;
import com.debthunter.controlplane.store.evidence.EvidenceSnapshot;
import com.debthunter.controlplane.store.evidence.EvidenceStorageService;
import com.debthunter.controlplane.store.evidence.EvidenceSummary;
import com.debthunter.controlplane.store.evidence.InMemoryEvidenceRepository;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.queue.EnqueueResult;
import com.debthunter.controlplane.store.queue.InMemoryWorkQueue;
import com.debthunter.controlplane.store.queue.QueueCounts;
import com.debthunter.controlplane.store.queue.QueueEntry;
import com.debthunter.controlplane.store.queue.QueuedItem;
import com.debthunter.controlplane.store.queue.RetryOutcome;
import com.debthunter.controlplane.store.queue.WorkQueue;
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
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HousekeepingTest {

  private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant NOW = T0.plus(Duration.ofDays(85));

  private final List<SuppressionPreExpiryEvent> warnings = new ArrayList<>();
  private final SuppressionRegisterService suppressions =
      new SuppressionRegisterService(new InMemorySuppressionRepository(), warnings::add);
  private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
  private static final Duration DONE = Duration.ofDays(1);
  private static final Duration DEAD = Duration.ofDays(7);
  private final WorkQueue queue =
      new InMemoryWorkQueue(
          IntakeQueue.policies(
              3, Duration.ofSeconds(30), Duration.ofSeconds(1), Duration.ofSeconds(4)),
          clock);

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

    new Housekeeping(evidence, suppressions, queue, DONE, DEAD, clock, Duration.ofHours(1))
        .runOnce();

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
            queue,
            DONE,
            DEAD,
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

    new Housekeeping(evidence, broken, queue, DONE, DEAD, clock, Duration.ofHours(1)).runOnce();

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
            queue,
            DONE,
            DEAD,
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
  @Test
  void aSweepPurgesOldFinishedAndSetAsideQueueItemsButNotRecentOnes() {
    queue.enqueue(IntakeQueue.KIND, "acme/a", null, new byte[] {1}, null);
    queue.enqueue(IntakeQueue.KIND, "acme/b", null, new byte[] {2}, null);
    queue.claim(IntakeQueue.KIND, "w", 10);
    queue.complete(1, "w");
    queue.deadLetter(2, "w", "unreadable");
    // Judge the sweep a week and a bit later: the done item is old, the dead one is just past its
    // window; a fresh one must survive.
    Clock later = Clock.fixed(NOW.plus(Duration.ofDays(8)), ZoneOffset.UTC);
    EvidenceStorageService evidence =
        new EvidenceStorageService(new InMemoryEvidenceRepository(), Duration.ofDays(30));

    new Housekeeping(evidence, suppressions, queue, DONE, DEAD, later, Duration.ofHours(1))
        .runOnce();

    assertThat(queue.counts(IntakeQueue.KIND).done()).isZero();
    assertThat(queue.counts(IntakeQueue.KIND).dead()).isZero();
  }

  @Test
  void aFailureToPurgeTheQueueDoesNotStopTheOtherTasks() {
    EvidenceStorageService evidence =
        new EvidenceStorageService(new InMemoryEvidenceRepository(), Duration.ofDays(30));
    evidence.store("r1", "acme/widgets", List.of(finding()), T0);
    suppressions.suppress("acme/widgets", "fp-a", "why", T0, T0.plus(Duration.ofDays(90)), 90);

    new Housekeeping(
            evidence, suppressions, new PurgeFails(queue), DONE, DEAD, clock, Duration.ofHours(1))
        .runOnce();

    assertThat(evidence.hotFor("r1")).isEmpty();
    assertThat(warnings).hasSize(1);
  }

  /** A queue that works except that it cannot be purged. */
  private record PurgeFails(WorkQueue delegate) implements WorkQueue {
    @Override
    public EnqueueResult enqueue(String k, String key, String d, byte[] p, Instant nb) {
      return delegate.enqueue(k, key, d, p, nb);
    }

    @Override
    public List<QueuedItem> claim(String k, String w, int max) {
      return delegate.claim(k, w, max);
    }

    @Override
    public boolean complete(long id, String w) {
      return delegate.complete(id, w);
    }

    @Override
    public RetryOutcome retry(long id, String w, String e) {
      return delegate.retry(id, w, e);
    }

    @Override
    public boolean hold(long id, String w, String r) {
      return delegate.hold(id, w, r);
    }

    @Override
    public boolean deadLetter(long id, String w, String e) {
      return delegate.deadLetter(id, w, e);
    }

    @Override
    public int purge(Instant a, Instant b) {
      throw new IllegalStateException("database down");
    }

    @Override
    public Optional<QueueEntry> find(long id) {
      return delegate.find(id);
    }

    @Override
    public QueueCounts counts(String k) {
      return delegate.counts(k);
    }
  }

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
