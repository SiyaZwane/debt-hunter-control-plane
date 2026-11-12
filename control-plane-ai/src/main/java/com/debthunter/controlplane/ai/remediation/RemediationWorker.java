package com.debthunter.controlplane.ai.remediation;

import com.debthunter.controlplane.ai.remediation.host.SourceHostRejectedException;
import com.debthunter.controlplane.ai.remediation.host.SourceHostUnavailableException;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.findings.PseudonymisedFindingStore;
import com.debthunter.controlplane.store.queue.QueuedItem;
import com.debthunter.controlplane.store.queue.RetryOutcome;
import com.debthunter.controlplane.store.queue.WorkQueue;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Works the remediation queue: for each request it checks the repository's rate limit and opt-in,
 * writes a proposal, and opens the pull request.
 *
 * <p>What happens to a request:
 *
 * <ul>
 *   <li>Over the repository's rate limit: put back until later, without counting an attempt. It is
 *       never dropped.
 *   <li>The source host is down, slow or rate-limiting: held without counting an attempt, for as
 *       long as that lasts.
 *   <li>The host refuses, or nothing could write a proposal: retried, and set aside after the
 *       queue's maximum attempts.
 *   <li>The repository has since opted out, the finding has gone, or the host's protection forbids
 *       the branch: set aside at once, because trying again cannot help.
 *   <li>A pull request opened, or one already open: finished.
 * </ul>
 */
public final class RemediationWorker implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(RemediationWorker.class);

  private final WorkQueue queue;
  private final RemediationEnabledLookup enabled;
  private final PseudonymisedFindingStore findings;
  private final RemediationAdvisor advisor;
  private final FixAgentPrCreator creator;
  private final RemediationRateLimit limit;
  private final Clock clock;
  private final String workerId;
  private final int batchSize;
  private final java.time.Duration pollInterval;
  private final CountDownLatch stopped = new CountDownLatch(1);
  private Thread thread;

  /**
   * Creates the worker.
   *
   * @param queue the durable queue
   * @param enabled which repositories have opted in
   * @param findings the finding store
   * @param advisor writes the proposal
   * @param creator opens the pull request
   * @param limit the per-repository rate limit
   * @param clock the source of "now"
   * @param workerId who this worker is
   * @param batchSize the most requests taken at a time
   * @param pollInterval how long to wait when the queue has nothing ready
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public RemediationWorker(
      WorkQueue queue,
      RemediationEnabledLookup enabled,
      PseudonymisedFindingStore findings,
      RemediationAdvisor advisor,
      FixAgentPrCreator creator,
      RemediationRateLimit limit,
      Clock clock,
      String workerId,
      int batchSize,
      java.time.Duration pollInterval) {
    this.queue = Objects.requireNonNull(queue, "queue");
    this.enabled = Objects.requireNonNull(enabled, "enabled");
    this.findings = Objects.requireNonNull(findings, "findings");
    this.advisor = Objects.requireNonNull(advisor, "advisor");
    this.creator = Objects.requireNonNull(creator, "creator");
    this.limit = Objects.requireNonNull(limit, "limit");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.workerId = Objects.requireNonNull(workerId, "workerId");
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be at least 1");
    }
    this.batchSize = batchSize;
    this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
  }

  /**
   * Takes one batch and deals with it.
   *
   * @return how many requests were taken; zero means nothing was ready
   */
  public int runOnce() {
    List<QueuedItem> items = queue.claim(RemediationQueue.KIND, workerId, batchSize);
    for (QueuedItem item : items) {
      process(item);
    }
    return items.size();
  }

  private void process(QueuedItem item) {
    RemediationJob job;
    try {
      job = RemediationJob.decode(item.payload());
    } catch (RuntimeException e) {
      queue.deadLetter(item.id(), workerId, "unreadable request");
      return;
    }

    Instant now = clock.instant();
    int used =
        queue.countStarted(
            RemediationQueue.KIND, job.repository(), now.minus(limit.window()), item.id());
    if (used >= limit.maxPerWindow()) {
      queue.defer(item.id(), workerId, now.plus(limit.recheck()));
      log.info(
          "remediation request for {} waits: {} pull request(s) already used the limit",
          job.repository(),
          used);
      return;
    }

    try {
      if (!enabled.enabled(job.repository())) {
        queue.deadLetter(item.id(), workerId, "the repository is not enabled for remediation");
        return;
      }
    } catch (RuntimeException e) {
      // The opt-in could not be checked: not the request's fault, and not consent. Wait.
      queue.hold(item.id(), workerId, "held: opt-in unavailable");
      return;
    }

    Optional<PseudonymisedFinding> finding;
    try {
      finding =
          findings.findingsForRun(job.runId()).stream()
              .filter(f -> f.id().equals(job.findingId()))
              .findFirst();
    } catch (RuntimeException e) {
      queue.hold(item.id(), workerId, "held: findings unavailable");
      return;
    }
    if (finding.isEmpty()) {
      queue.deadLetter(item.id(), workerId, "the finding is no longer available");
      return;
    }

    RemediationRequest request =
        new RemediationRequest(job.repository(), job.runId(), finding.get(), job.baseBranch());
    Optional<RemediationProposal> proposal = advisor.propose(request);
    if (proposal.isEmpty()) {
      retry(item, "no proposal could be written");
      return;
    }
    try {
      PrOutcome outcome = creator.create(request, proposal.get());
      if (outcome instanceof PrOutcome.Refused refused) {
        queue.deadLetter(item.id(), workerId, "refused: " + refused.reason());
        log.warn(
            "no remediation pull request for {} finding {}: {}",
            job.repository(),
            job.findingId(),
            refused.reason());
        return;
      }
      queue.complete(item.id(), workerId);
    } catch (SourceHostUnavailableException e) {
      queue.hold(item.id(), workerId, "held: source host unavailable");
      log.warn("source host unavailable for {}; holding the request", job.repository());
    } catch (SourceHostRejectedException e) {
      retry(item, "source host refused: " + e.getMessage());
    } catch (RuntimeException e) {
      log.error(
          "remediation request failed repository={} finding={}",
          job.repository(),
          job.findingId(),
          e);
      retry(item, "failed: " + e.getClass().getSimpleName());
    }
  }

  private void retry(QueuedItem item, String reason) {
    RetryOutcome outcome = queue.retry(item.id(), workerId, reason);
    if (outcome == RetryOutcome.DEAD_LETTERED) {
      log.error(
          "remediation request {} set aside after {} attempts: {}",
          item.id(),
          item.attempts(),
          reason);
    }
  }

  /** Starts working on a background thread. Idempotent. */
  public synchronized void start() {
    if (thread != null) {
      return;
    }
    thread = new Thread(this::run, "remediation-worker");
    thread.setDaemon(true);
    thread.start();
  }

  private void run() {
    while (stopped.getCount() > 0) {
      try {
        if (runOnce() == 0 && pause(pollInterval)) {
          return;
        }
      } catch (RuntimeException e) {
        log.warn("the remediation queue could not be read; will retry", e);
        if (pause(java.time.Duration.ofSeconds(1))) {
          return;
        }
      }
    }
  }

  private boolean pause(java.time.Duration duration) {
    try {
      return stopped.await(duration.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return true;
    }
  }

  /** Stops working and waits for the current request to finish. Idempotent. */
  @Override
  public void close() {
    Thread working;
    synchronized (this) {
      stopped.countDown();
      working = thread;
      thread = null;
    }
    if (working != null) {
      try {
        working.join(10_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
