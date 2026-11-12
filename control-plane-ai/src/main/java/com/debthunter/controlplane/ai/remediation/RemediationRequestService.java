package com.debthunter.controlplane.ai.remediation;

import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.findings.PseudonymisedFindingStore;
import com.debthunter.controlplane.store.queue.EnqueueResult;
import com.debthunter.controlplane.store.queue.WorkQueue;
import com.debthunter.controlplane.store.runs.RunRecord;
import com.debthunter.controlplane.store.runs.RunRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Takes a request for a remediation proposal and puts it on the durable queue. Nothing is opened
 * here: the request is accepted if the repository has opted in and the finding exists, and a worker
 * does the rest at its own pace. Nothing on a pipeline's path calls this.
 */
public final class RemediationRequestService {

  private static final Logger LOG = LoggerFactory.getLogger(RemediationRequestService.class);
  private static final Pattern BRANCH = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._/-]{0,99}");

  private final RemediationEnabledLookup enabled;
  private final RunRepository runs;
  private final PseudonymisedFindingStore findings;
  private final WorkQueue queue;

  /**
   * Creates the service.
   *
   * @param enabled which repositories have opted in
   * @param runs the run store
   * @param findings the finding store
   * @param queue the durable queue
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public RemediationRequestService(
      RemediationEnabledLookup enabled,
      RunRepository runs,
      PseudonymisedFindingStore findings,
      WorkQueue queue) {
    this.enabled = Objects.requireNonNull(enabled, "enabled");
    this.runs = Objects.requireNonNull(runs, "runs");
    this.findings = Objects.requireNonNull(findings, "findings");
    this.queue = Objects.requireNonNull(queue, "queue");
  }

  /**
   * Asks for a remediation proposal.
   *
   * @param repository the repository
   * @param runId the run the finding was reported in
   * @param findingId the finding
   * @param baseBranch the branch the pull request should target
   * @param requestedBy who is asking
   * @return queued, or why not
   */
  public RequestOutcome request(
      String repository, String runId, String findingId, String baseBranch, String requestedBy) {
    if (baseBranch == null || !BRANCH.matcher(baseBranch).matches() || baseBranch.contains("..")) {
      return new RequestOutcome.Refused(RequestOutcome.Reason.INVALID_BASE_BRANCH);
    }
    try {
      if (!enabled.enabled(repository)) {
        return new RequestOutcome.Refused(RequestOutcome.Reason.NOT_ENABLED);
      }
    } catch (RuntimeException e) {
      LOG.warn("Could not check whether {} has opted in to remediation", repository, e);
      return new RequestOutcome.Refused(RequestOutcome.Reason.ENABLEMENT_UNAVAILABLE);
    }
    Optional<RunRecord> run = runs.find(runId);
    if (run.isEmpty() || !run.get().repository().equals(repository)) {
      return new RequestOutcome.Refused(RequestOutcome.Reason.NOT_FOUND);
    }
    Optional<PseudonymisedFinding> finding =
        findings.findingsForRun(runId).stream().filter(f -> f.id().equals(findingId)).findFirst();
    if (finding.isEmpty()) {
      return new RequestOutcome.Refused(RequestOutcome.Reason.NOT_FOUND);
    }
    EnqueueResult result =
        queue.enqueue(
            RemediationQueue.KIND,
            repository,
            repository + ":" + finding.get().fingerprint(),
            new RemediationJob(repository, runId, findingId, baseBranch, requestedBy).encode(),
            null);
    return new RequestOutcome.Queued(result.id(), result.duplicate());
  }
}
