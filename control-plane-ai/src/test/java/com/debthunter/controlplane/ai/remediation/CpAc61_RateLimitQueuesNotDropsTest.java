package com.debthunter.controlplane.ai.remediation;

import static com.debthunter.controlplane.ai.remediation.RemediationFixture.REPO;
import static com.debthunter.controlplane.ai.remediation.RemediationFixture.finding;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.queue.QueueCounts;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-61: a repository's rate limit queues the excess, it does not drop it. Every request that is
 * accepted ends up as a pull request, however many are asked for at once.
 */
class CpAc61_RateLimitQueuesNotDropsTest {

  private final RemediationFixture f = new RemediationFixture();

  private void requestFive() {
    var findings = new com.debthunter.controlplane.store.findings.PseudonymisedFinding[5];
    for (int i = 0; i < 5; i++) {
      findings[i] = finding(i);
    }
    f.storeRun("run-1", REPO, findings);
    for (var finding : findings) {
      assertThat(f.request(REPO, "run-1", finding)).isInstanceOf(RequestOutcome.Queued.class);
    }
  }

  @Test
  void cpAc61_overTheLimitRequestsWaitOnTheQueueAndNoneIsDropped() {
    requestFive();

    f.worker.runOnce();

    assertThat(f.host.pullRequests).as("the limit is two per day").hasSize(2);
    QueueCounts counts = f.queue.counts(RemediationQueue.KIND);
    assertThat(counts.done()).isEqualTo(2);
    assertThat(counts.pending()).as("three are waiting, not dropped").isEqualTo(3);
    assertThat(counts.dead()).isZero();
  }

  @Test
  void cpAc61_theWaitingRequestsAreOpenedAsTheWindowAllowsUntilAllAreDone() {
    requestFive();

    f.worker.runOnce();
    assertThat(f.host.pullRequests).hasSize(2);

    // Looked at again after the recheck, but still within the day: still waiting.
    f.clock.advance(Duration.ofHours(2));
    f.worker.runOnce();
    assertThat(f.host.pullRequests).hasSize(2);

    // A day on, the first two have aged out of the window.
    f.clock.advance(Duration.ofDays(1));
    f.worker.runOnce();
    assertThat(f.host.pullRequests).hasSize(4);

    // A pull request exactly one window old still counts, so go a little past it.
    f.clock.advance(Duration.ofDays(1).plusMinutes(1));
    f.worker.runOnce();
    assertThat(f.host.pullRequests).hasSize(5);
    assertThat(f.queue.counts(RemediationQueue.KIND)).isEqualTo(new QueueCounts(0, 0, 5, 0));
  }

  @Test
  void cpAc61_waitingDoesNotSpendAttemptsSoItNeverEndsInTheDeadLetters() {
    requestFive();

    for (int i = 0; i < 40; i++) {
      f.worker.runOnce();
      f.clock.advance(Duration.ofHours(2));
    }

    assertThat(f.queue.counts(RemediationQueue.KIND).dead()).isZero();
    assertThat(f.host.pullRequests).hasSize(5);
  }

  @Test
  void cpAc61_theLimitIsPerRepositoryOneBusyRepositoryDoesNotHoldBackAnother() {
    f.storeRun("run-1", REPO, finding(0), finding(1), finding(2), finding(3));
    f.storeRun("run-2", "acme/other", finding(10));
    for (int i = 0; i < 4; i++) {
      f.request(REPO, "run-1", finding(i));
    }
    f.request("acme/other", "run-2", finding(10));

    f.worker.runOnce();

    assertThat(f.host.pullRequests)
        .extracting(p -> p.repository())
        .containsOnly(REPO, "acme/other");
    assertThat(f.host.pullRequests.stream().filter(p -> p.repository().equals("acme/other")))
        .hasSize(1);
    assertThat(f.host.pullRequests.stream().filter(p -> p.repository().equals(REPO))).hasSize(2);
  }

  @Test
  void cpAc61_aRequestedFindingIsOnlyQueuedOnce() {
    f.storeRun("run-1", REPO, finding(0));

    var first = f.request(REPO, "run-1", finding(0));
    var second = f.request(REPO, "run-1", finding(0));

    assertThat(second)
        .isInstanceOfSatisfying(
            RequestOutcome.Queued.class, q -> assertThat(q.alreadyQueued()).isTrue());
    assertThat(((RequestOutcome.Queued) first).queueId())
        .isEqualTo(((RequestOutcome.Queued) second).queueId());
    f.worker.runOnce();
    assertThat(f.host.pullRequests).hasSize(1);
  }
}
