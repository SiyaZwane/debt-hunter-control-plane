package com.debthunter.controlplane.store.queue;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

abstract class WorkQueueContract {

  static final String ORDERED = "ordered";
  static final String FREE = "free";

  static final Map<String, QueuePolicy> POLICIES =
      Map.of(
          ORDERED,
          new QueuePolicy(
              true, 3, Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(40)),
          FREE,
          new QueuePolicy(
              false, 3, Duration.ofSeconds(30), Duration.ofSeconds(10), Duration.ofSeconds(40)));

  protected abstract WorkQueue newQueue(TestClock clock);

  protected TestClock clock;
  protected WorkQueue queue;

  @BeforeEach
  void create() {
    clock = new TestClock();
    queue = newQueue(clock);
  }

  private static byte[] bytes(String s) {
    return s.getBytes(StandardCharsets.UTF_8);
  }

  private long add(String kind, String key, String payload) {
    return queue.enqueue(kind, key, null, bytes(payload), null).id();
  }

  // ---- claiming ------------------------------------------------------------------------------

  @Test
  void anEnqueuedItemIsClaimedWithItsPayloadAndCountedAsTried() {
    long id = add(ORDERED, "acme/a", "hello");

    List<QueuedItem> claimed = queue.claim(ORDERED, "w1", 10);

    assertThat(claimed)
        .singleElement()
        .satisfies(
            i -> {
              assertThat(i.id()).isEqualTo(id);
              assertThat(i.key()).isEqualTo("acme/a");
              assertThat(i.payload()).isEqualTo(bytes("hello"));
              assertThat(i.attempts()).isEqualTo(1);
            });
  }

  @Test
  void anItemInProgressIsNotHandedOutAgain() {
    add(FREE, "k", "x");
    queue.claim(FREE, "w1", 10);

    assertThat(queue.claim(FREE, "w2", 10)).isEmpty();
  }

  @Test
  void claimingTakesOldestFirstAndNoMoreThanAsked() {
    long first = add(FREE, "a", "1");
    long second = add(FREE, "b", "2");
    add(FREE, "c", "3");

    assertThat(queue.claim(FREE, "w", 2)).extracting(QueuedItem::id).containsExactly(first, second);
  }

  @Test
  void aKindOnlySeesItsOwnItems() {
    add(FREE, "a", "1");

    assertThat(queue.claim(ORDERED, "w", 10)).isEmpty();
  }

  @Test
  void anItemDelayedUntilLaterIsNotClaimableUntilThen() {
    queue.enqueue(FREE, "a", null, bytes("x"), clock.instant().plusSeconds(60));

    assertThat(queue.claim(FREE, "w", 10)).isEmpty();
    clock.advance(Duration.ofSeconds(61));
    assertThat(queue.claim(FREE, "w", 10)).hasSize(1);
  }

  // ---- finishing -----------------------------------------------------------------------------

  @Test
  void completingAnItemErasesItsPayloadButKeepsTheBookkeeping() {
    long id = add(FREE, "a", "secret contributor data");
    queue.claim(FREE, "w", 10);

    assertThat(queue.complete(id, "w")).isTrue();

    QueueEntry entry = queue.find(id).orElseThrow();
    assertThat(entry.status()).isEqualTo(QueueEntry.Status.DONE);
    assertThat(entry.payloadBytes()).isZero();
    assertThat(queue.counts(FREE)).isEqualTo(new QueueCounts(0, 0, 1, 0));
  }

  @Test
  void onlyTheWorkerHoldingAnItemCanFinishIt() {
    long id = add(FREE, "a", "x");
    queue.claim(FREE, "w1", 10);

    assertThat(queue.complete(id, "w2")).isFalse();
    assertThat(queue.deadLetter(id, "w2", "no")).isFalse();
    assertThat(queue.retry(id, "w2", "no")).isEqualTo(RetryOutcome.LEASE_LOST);
    assertThat(queue.find(id).orElseThrow().status()).isEqualTo(QueueEntry.Status.IN_PROGRESS);
  }

  @Test
  void anItemThatWasNeverClaimedCannotBeFinished() {
    long id = add(FREE, "a", "x");

    assertThat(queue.complete(id, "w")).isFalse();
  }

  // ---- retry, backoff, dead-lettering ---------------------------------------------------------

  @Test
  void aFailedAttemptIsRetriedAfterABackoffAndRemembersWhy() {
    long id = add(FREE, "a", "x");
    queue.claim(FREE, "w", 10);

    assertThat(queue.retry(id, "w", "database hiccup")).isEqualTo(RetryOutcome.RETRY_SCHEDULED);

    assertThat(queue.claim(FREE, "w", 10)).as("still backing off").isEmpty();
    clock.advance(Duration.ofSeconds(11));
    List<QueuedItem> again = queue.claim(FREE, "w", 10);
    assertThat(again).singleElement().satisfies(i -> assertThat(i.attempts()).isEqualTo(2));
    assertThat(queue.find(id).orElseThrow().lastError()).isEqualTo("database hiccup");
  }

  @Test
  void theBackoffGrowsWithEachFailureUpToTheLimit() {
    long id = add(FREE, "a", "x");
    queue.claim(FREE, "w", 10);
    queue.retry(id, "w", "e");
    clock.advance(Duration.ofSeconds(11));
    queue.claim(FREE, "w", 10);
    queue.retry(id, "w", "e");

    clock.advance(Duration.ofSeconds(11));
    assertThat(queue.claim(FREE, "w", 10)).as("second wait is 20s").isEmpty();
    clock.advance(Duration.ofSeconds(10));
    assertThat(queue.claim(FREE, "w", 10)).hasSize(1);
  }

  @Test
  void anItemThatUsesUpItsAttemptsIsSetAsideAsDead() {
    long id = add(FREE, "a", "x");
    RetryOutcome last = null;
    for (int i = 0; i < 3; i++) {
      clock.advance(Duration.ofMinutes(5));
      queue.claim(FREE, "w", 10);
      last = queue.retry(id, "w", "attempt " + (i + 1));
    }

    assertThat(last).isEqualTo(RetryOutcome.DEAD_LETTERED);
    QueueEntry entry = queue.find(id).orElseThrow();
    assertThat(entry.status()).isEqualTo(QueueEntry.Status.DEAD);
    assertThat(entry.attempts()).isEqualTo(3);
    assertThat(entry.lastError()).isEqualTo("attempt 3");
    clock.advance(Duration.ofDays(1));
    assertThat(queue.claim(FREE, "w", 10)).isEmpty();
  }

  @Test
  void aHeldItemIsNeverSetAsideHoweverOftenItIsHeldAndItsAttemptsAreNotSpent() {
    long id = add(FREE, "a", "x");
    for (int i = 0; i < 20; i++) {
      assertThat(queue.claim(FREE, "w", 10)).hasSize(1);
      assertThat(queue.hold(id, "w", "key store down")).isTrue();
      clock.advance(Duration.ofSeconds(41));
    }

    QueueEntry entry = queue.find(id).orElseThrow();
    assertThat(entry.status()).isEqualTo(QueueEntry.Status.PENDING);
    assertThat(entry.attempts()).isZero();
    assertThat(entry.lastError()).isEqualTo("key store down");
    assertThat(queue.claim(FREE, "w", 10).get(0).attempts()).isEqualTo(1);
  }

  @Test
  void aHeldItemWaitsForTheMaximumBackoffBeforeItIsOfferedAgain() {
    long id = add(FREE, "a", "x");
    queue.claim(FREE, "w", 10);
    queue.hold(id, "w", "down");

    clock.advance(Duration.ofSeconds(39));
    assertThat(queue.claim(FREE, "w", 10)).isEmpty();
    clock.advance(Duration.ofSeconds(2));
    assertThat(queue.claim(FREE, "w", 10)).hasSize(1);
  }

  @Test
  void aHeldItemStillBlocksLaterItemsOfItsOrderedKey() {
    long first = add(ORDERED, "acme/a", "1");
    add(ORDERED, "acme/a", "2");
    queue.claim(ORDERED, "w", 10);

    queue.hold(first, "w", "down");

    assertThat(queue.claim(ORDERED, "w", 10)).isEmpty();
  }

  @Test
  void onlyTheHolderCanHoldAnItem() {
    long id = add(FREE, "a", "x");
    queue.claim(FREE, "w1", 10);

    assertThat(queue.hold(id, "w2", "down")).isFalse();
    assertThat(queue.find(id).orElseThrow().status()).isEqualTo(QueueEntry.Status.IN_PROGRESS);
  }

  @Test
  void aDeferredItemWaitsUntilTheChosenTimeWithoutSpendingAnAttempt() {
    long id = add(FREE, "a", "x");
    queue.claim(FREE, "w", 10);

    assertThat(queue.defer(id, "w", clock.instant().plusSeconds(100))).isTrue();

    assertThat(queue.find(id).orElseThrow().attempts()).isZero();
    clock.advance(Duration.ofSeconds(99));
    assertThat(queue.claim(FREE, "w", 10)).isEmpty();
    clock.advance(Duration.ofSeconds(2));
    assertThat(queue.claim(FREE, "w", 10)).hasSize(1);
  }

  @Test
  void onlyTheHolderCanDeferAnItem() {
    long id = add(FREE, "a", "x");
    queue.claim(FREE, "w1", 10);

    assertThat(queue.defer(id, "w2", clock.instant().plusSeconds(5))).isFalse();
  }

  @Test
  void countStartedCountsRecentlyFinishedAndInProgressItemsForOneKeyOnly() {
    long done = add(FREE, "acme/a", "1");
    add(FREE, "acme/a", "2");
    add(FREE, "acme/b", "3");
    queue.claim(FREE, "w", 2);
    queue.complete(done, "w");
    clock.advance(Duration.ofHours(1));
    add(FREE, "acme/a", "4");
    queue.claim(FREE, "w", 10);

    assertThat(queue.countStarted(FREE, "acme/a", clock.instant().minus(Duration.ofHours(2)), -1))
        .as("one done, two in progress")
        .isEqualTo(3);
    assertThat(
            queue.countStarted(FREE, "acme/a", clock.instant().minus(Duration.ofMinutes(30)), -1))
        .as("the done one is outside the window")
        .isEqualTo(2);
    assertThat(queue.countStarted(FREE, "acme/b", clock.instant().minus(Duration.ofHours(2)), -1))
        .isEqualTo(1);
  }

  @Test
  void countStartedLeavesOutTheItemAsking() {
    long id = add(FREE, "acme/a", "1");
    queue.claim(FREE, "w", 10);

    assertThat(queue.countStarted(FREE, "acme/a", clock.instant().minusSeconds(60), id)).isZero();
  }

  @Test
  void countStartedIgnoresWaitingAndDeadItems() {
    long dead = add(FREE, "acme/a", "1");
    add(FREE, "acme/a", "2");
    queue.claim(FREE, "w", 1);
    queue.deadLetter(dead, "w", "bad");

    assertThat(queue.countStarted(FREE, "acme/a", clock.instant().minus(Duration.ofDays(1)), -1))
        .isZero();
  }

  @Test
  void aDeadLetteredItemKeepsItsPayloadForInvestigationUntilPurged() {
    long id = add(FREE, "a", "evidence");
    queue.claim(FREE, "w", 10);

    assertThat(queue.deadLetter(id, "w", "unreadable")).isTrue();

    QueueEntry entry = queue.find(id).orElseThrow();
    assertThat(entry.status()).isEqualTo(QueueEntry.Status.DEAD);
    assertThat(entry.payloadBytes()).isEqualTo(bytes("evidence").length);
    assertThat(entry.lastError()).isEqualTo("unreadable");
  }

  // ---- leases --------------------------------------------------------------------------------

  @Test
  void anItemWhoseWorkerDiedIsHandedToAnotherAfterTheLeaseExpires() {
    long id = add(FREE, "a", "x");
    queue.claim(FREE, "dead-worker", 10);
    clock.advance(Duration.ofSeconds(31));

    List<QueuedItem> taken = queue.claim(FREE, "w2", 10);

    assertThat(taken)
        .singleElement()
        .satisfies(
            i -> {
              assertThat(i.id()).isEqualTo(id);
              assertThat(i.attempts()).as("the lost attempt counts").isEqualTo(2);
            });
    assertThat(queue.complete(id, "dead-worker")).as("the old holder lost it").isFalse();
    assertThat(queue.complete(id, "w2")).isTrue();
  }

  @Test
  void aLeaseThatHasNotExpiredIsRespected() {
    add(FREE, "a", "x");
    queue.claim(FREE, "w1", 10);
    clock.advance(Duration.ofSeconds(29));

    assertThat(queue.claim(FREE, "w2", 10)).isEmpty();
  }

  // ---- ordering ------------------------------------------------------------------------------

  @Test
  void inAnOrderedKindALaterItemWaitsForTheEarlierOneWithTheSameKey() {
    long first = add(ORDERED, "acme/a", "1");
    add(ORDERED, "acme/a", "2");
    long other = add(ORDERED, "acme/b", "3");

    List<QueuedItem> claimed = queue.claim(ORDERED, "w", 10);

    assertThat(claimed).extracting(QueuedItem::id).containsExactly(first, other);
  }

  @Test
  void theNextItemIsReleasedWhenTheEarlierOneIsDone() {
    long first = add(ORDERED, "acme/a", "1");
    long second = add(ORDERED, "acme/a", "2");
    queue.claim(ORDERED, "w", 10);

    queue.complete(first, "w");

    assertThat(queue.claim(ORDERED, "w", 10)).extracting(QueuedItem::id).containsExactly(second);
  }

  @Test
  void anEarlierItemBackingOffHoldsUpOnlyItsOwnKey() {
    long first = add(ORDERED, "acme/a", "1");
    add(ORDERED, "acme/a", "2");
    long other = add(ORDERED, "acme/b", "3");
    queue.claim(ORDERED, "w", 10);
    queue.retry(first, "w", "boom");
    queue.complete(other, "w");
    long later = add(ORDERED, "acme/b", "4");

    assertThat(queue.claim(ORDERED, "w", 10)).extracting(QueuedItem::id).containsExactly(later);
  }

  @Test
  void anEarlierItemThatDiesReleasesTheNextOne() {
    long first = add(ORDERED, "acme/a", "1");
    long second = add(ORDERED, "acme/a", "2");
    queue.claim(ORDERED, "w", 10);

    queue.deadLetter(first, "w", "unreadable");

    assertThat(queue.claim(ORDERED, "w", 10)).extracting(QueuedItem::id).containsExactly(second);
  }

  @Test
  void anUnorderedKindHandsOutSameKeyItemsTogether() {
    add(FREE, "acme/a", "1");
    add(FREE, "acme/a", "2");

    assertThat(queue.claim(FREE, "w", 10)).hasSize(2);
  }

  // ---- de-duplication ------------------------------------------------------------------------

  @Test
  void anItemWithTheSameDedupKeyAsOneStillWaitingIsNotAddedTwice() {
    EnqueueResult first = queue.enqueue(FREE, "a", "fp-1", bytes("x"), null);
    EnqueueResult second = queue.enqueue(FREE, "a", "fp-1", bytes("y"), null);

    assertThat(first.duplicate()).isFalse();
    assertThat(second.duplicate()).isTrue();
    assertThat(second.id()).isEqualTo(first.id());
    assertThat(queue.counts(FREE).pending()).isEqualTo(1);
  }

  @Test
  void aDedupKeyCanBeReusedOnceTheEarlierItemIsFinished() {
    EnqueueResult first = queue.enqueue(FREE, "a", "fp-1", bytes("x"), null);
    queue.claim(FREE, "w", 10);
    queue.complete(first.id(), "w");

    EnqueueResult again = queue.enqueue(FREE, "a", "fp-1", bytes("x"), null);

    assertThat(again.duplicate()).isFalse();
    assertThat(again.id()).isNotEqualTo(first.id());
  }

  @Test
  void dedupKeysAreScopedToTheirKind() {
    queue.enqueue(FREE, "a", "fp-1", bytes("x"), null);

    assertThat(queue.enqueue(ORDERED, "a", "fp-1", bytes("x"), null).duplicate()).isFalse();
  }

  // ---- purging -------------------------------------------------------------------------------

  @Test
  void purgeDeletesOldFinishedAndOldDeadItemsOnly() {
    long done = add(FREE, "a", "x");
    long dead = add(FREE, "b", "y");
    queue.claim(FREE, "w", 10);
    queue.complete(done, "w");
    queue.deadLetter(dead, "w", "bad");
    clock.advance(Duration.ofDays(2));
    long recentDone = add(FREE, "c", "z");
    queue.claim(FREE, "w", 10);
    queue.complete(recentDone, "w");

    int deleted =
        queue.purge(
            clock.instant().minus(Duration.ofDays(1)), clock.instant().minus(Duration.ofDays(7)));

    assertThat(deleted)
        .as("the old done item only; the dead one is within its retention")
        .isEqualTo(1);
    assertThat(queue.find(done)).isEmpty();
    assertThat(queue.find(dead)).isPresent();
    assertThat(queue.find(recentDone)).isPresent();

    clock.advance(Duration.ofDays(8));
    assertThat(
            queue.purge(
                clock.instant().minus(Duration.ofDays(1)),
                clock.instant().minus(Duration.ofDays(7))))
        .isEqualTo(2);
    assertThat(queue.find(dead)).isEmpty();
  }

  @Test
  void purgeNeverTouchesWaitingOrInProgressItems() {
    add(FREE, "a", "x");
    add(FREE, "b", "y");
    queue.claim(FREE, "w", 1);
    clock.advance(Duration.ofDays(100));

    assertThat(queue.purge(clock.instant(), clock.instant())).isZero();
    assertThat(queue.counts(FREE)).isEqualTo(new QueueCounts(1, 1, 0, 0));
  }

  // ---- misc ----------------------------------------------------------------------------------

  @Test
  void anUnknownKindIsRefused() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> queue.enqueue("nope", "k", null, bytes("x"), null));
    assertThatIllegalArgumentException().isThrownBy(() -> queue.claim("nope", "w", 1));
  }

  @Test
  void anUnknownItemIsEmpty() {
    assertThat(queue.find(424242)).isEmpty();
  }

  @Test
  void aClaimedPayloadIsACopy() {
    add(FREE, "a", "abc");
    byte[] claimed = queue.claim(FREE, "w", 1).get(0).payload();
    claimed[0] = 'z';
    clock.advance(Duration.ofSeconds(31));

    assertThat(queue.claim(FREE, "w2", 1).get(0).payload()).isEqualTo(bytes("abc"));
  }

  @Test
  void theBackoffPolicyDoublesAndCaps() {
    QueuePolicy p = POLICIES.get(FREE);

    assertThat(p.backoffAfter(1)).isEqualTo(Duration.ofSeconds(10));
    assertThat(p.backoffAfter(2)).isEqualTo(Duration.ofSeconds(20));
    assertThat(p.backoffAfter(3)).isEqualTo(Duration.ofSeconds(40));
    assertThat(p.backoffAfter(9)).isEqualTo(Duration.ofSeconds(40));
  }

  // ---- concurrency ---------------------------------------------------------------------------

  @Test
  void concurrentWorkersNeverTakeTheSameItem() throws Exception {
    for (int i = 0; i < 60; i++) {
      add(FREE, "k" + i, "p" + i);
    }
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<List<Long>>> futures = new ArrayList<>();
      for (int w = 0; w < 4; w++) {
        String worker = "w" + w;
        futures.add(
            pool.submit(
                () -> {
                  List<Long> mine = new ArrayList<>();
                  List<QueuedItem> batch;
                  while (!(batch = queue.claim(FREE, worker, 5)).isEmpty()) {
                    batch.forEach(i -> mine.add(i.id()));
                  }
                  return mine;
                }));
      }
      List<Long> all = new ArrayList<>();
      for (Future<List<Long>> f : futures) {
        all.addAll(f.get());
      }
      Set<Long> distinct = new HashSet<>(all);
      assertThat(all).hasSize(60);
      assertThat(distinct).hasSize(60);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentWorkersNeverRunTwoItemsOfAnOrderedKeyAtOnce() throws Exception {
    for (int i = 0; i < 20; i++) {
      add(ORDERED, "acme/one-key", "p" + i);
    }
    ExecutorService pool = Executors.newFixedThreadPool(4);
    try {
      List<Future<Integer>> futures = new ArrayList<>();
      for (int w = 0; w < 4; w++) {
        String worker = "w" + w;
        futures.add(pool.submit(() -> queue.claim(ORDERED, worker, 10).size()));
      }
      int total = 0;
      for (Future<Integer> f : futures) {
        total += f.get();
      }
      assertThat(total).isEqualTo(1);
      assertThat(Collections.max(List.of(total))).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
  }
}
