package com.debthunter.controlplane.ai.remediation;

import static com.debthunter.controlplane.ai.remediation.RemediationFixture.REPO;
import static com.debthunter.controlplane.ai.remediation.RemediationFixture.finding;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.debthunter.controlplane.ai.remediation.host.SourceHostRejectedException;
import com.debthunter.controlplane.ai.remediation.host.SourceHostUnavailableException;
import com.debthunter.controlplane.store.queue.QueueEntry;
import java.time.Duration;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RemediationWorkerTest {

  private final RemediationFixture f = new RemediationFixture();

  private void queueOne() {
    f.storeRun("run-1", REPO, finding(0));
    assertThat(f.request(REPO, "run-1", finding(0))).isInstanceOf(RequestOutcome.Queued.class);
  }

  private QueueEntry entry() {
    return f.queue.find(1).orElseThrow();
  }

  @Test
  void aQueuedRequestBecomesADraftPullRequestAndIsFinished() {
    queueOne();

    assertThat(f.worker.runOnce()).isEqualTo(1);

    assertThat(f.host.pullRequests).hasSize(1);
    assertThat(entry().status()).isEqualTo(QueueEntry.Status.DONE);
  }

  @Test
  void aQueueWithNothingReadyTakesNothing() {
    assertThat(f.worker.runOnce()).isZero();
  }

  @Test
  void aHostThatIsDownHoldsTheRequestForeverWithoutSpendingAttempts() {
    queueOne();
    f.host.failWith = new SourceHostUnavailableException("down", null);

    for (int i = 0; i < 25; i++) {
      f.worker.runOnce();
      f.clock.advance(Duration.ofSeconds(10));
    }

    assertThat(entry().status()).isEqualTo(QueueEntry.Status.PENDING);
    assertThat(entry().attempts()).isZero();

    f.host.failWith = null;
    f.worker.runOnce();
    assertThat(f.host.pullRequests).hasSize(1);
  }

  @Test
  void aHostThatKeepsRefusingEndsInTheDeadLettersAfterItsAttempts() {
    queueOne();
    f.host.failWith = new SourceHostRejectedException("no permission");

    for (int i = 0; i < 6; i++) {
      f.worker.runOnce();
      f.clock.advance(Duration.ofMinutes(1));
    }

    assertThat(entry().status()).isEqualTo(QueueEntry.Status.DEAD);
    assertThat(entry().attempts()).isEqualTo(3);
  }

  @Test
  void aRepositoryThatHasSinceOptedOutIsSetAsideAndNothingIsOpened() {
    queueOne();
    f.enabledRepositories.remove(REPO);

    f.worker.runOnce();

    assertThat(entry().status()).isEqualTo(QueueEntry.Status.DEAD);
    assertThat(entry().lastError()).contains("not enabled");
    assertThat(f.host.calls).isEmpty();
  }

  @Test
  void aCheckOfTheOptInThatFailsHoldsTheRequestRatherThanTreatingItAsConsent() {
    queueOne();
    f.enablementDown = true;

    f.worker.runOnce();

    assertThat(entry().status()).isEqualTo(QueueEntry.Status.PENDING);
    assertThat(f.host.calls).isEmpty();
  }

  @Test
  void aFindingThatHasGoneIsSetAsideAtOnce() {
    queueOne();
    f.findings.saveRun("run-1", REPO, java.util.List.of());

    f.worker.runOnce();

    assertThat(entry().status()).isEqualTo(QueueEntry.Status.DEAD);
    assertThat(entry().lastError()).contains("no longer available");
  }

  @Test
  void aProtectedBranchIsSetAsideAtOnceBecauseTryingAgainCannotHelp() {
    queueOne();
    f.host.protectedBranches.add(BranchNames.branchFor(REPO, "fp-0"));

    f.worker.runOnce();

    assertThat(entry().status()).isEqualTo(QueueEntry.Status.DEAD);
    assertThat(entry().lastError()).contains("PROTECTED_BRANCH");
    assertThat(f.host.wasWrittenTo()).isFalse();
  }

  @Test
  void aPullRequestThatAlreadyExistsFinishesTheRequestWithoutAnother() {
    queueOne();
    f.worker.runOnce();
    f.queue.enqueue(
        RemediationQueue.KIND,
        REPO,
        null,
        new RemediationJob(REPO, "run-1", finding(0).id(), "main", "bob").encode(),
        null);

    f.worker.runOnce();

    assertThat(f.host.pullRequests).hasSize(1);
    assertThat(f.queue.counts(RemediationQueue.KIND).done()).isEqualTo(2);
  }

  @Test
  void whenNoAdvisorCanWriteAProposalTheRequestIsRetriedThenSetAside() {
    queueOne();
    f.advisor = request -> Optional.empty();

    for (int i = 0; i < 5; i++) {
      f.worker.runOnce();
      f.clock.advance(Duration.ofMinutes(1));
    }

    assertThat(entry().status()).isEqualTo(QueueEntry.Status.DEAD);
    assertThat(f.host.calls).isEmpty();
  }

  @Test
  void anUnreadableRequestIsSetAsideAtOnce() {
    f.queue.enqueue(RemediationQueue.KIND, REPO, null, "not json".getBytes(), null);

    f.worker.runOnce();

    assertThat(entry().status()).isEqualTo(QueueEntry.Status.DEAD);
  }

  @Test
  void aBugInTheCreatorIsRetriedAndRecordedByTypeOnly() {
    queueOne();
    f.host.failWith = new IllegalStateException("secret detail that must not be stored");

    f.worker.runOnce();

    assertThat(entry().status()).isEqualTo(QueueEntry.Status.PENDING);
    assertThat(entry().lastError()).isEqualTo("failed: IllegalStateException");
  }

  @Test
  void theBackgroundWorkerDrainsTheQueueAndStopsWhenClosed() {
    queueOne();

    f.worker.start();
    f.worker.start();
    await().atMost(Duration.ofSeconds(10)).until(() -> !f.host.pullRequests.isEmpty());
    f.worker.close();
    f.worker.close();

    assertThat(f.host.pullRequests).hasSize(1);
  }

  @Test
  void aWorkerNeedsAPositiveBatchSize() {
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new RemediationWorker(
                    f.queue,
                    r -> true,
                    f.findings,
                    r -> Optional.empty(),
                    new FixAgentPrCreator(f.host, RunLinks.none()),
                    f.limit,
                    f.clock,
                    "w",
                    0,
                    Duration.ofMillis(10)));
  }
}
