package com.debthunter.controlplane.ai.remediation;

import com.debthunter.controlplane.ai.remediation.host.PullRequestRef;
import com.debthunter.controlplane.ai.remediation.host.SourceHostClient;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens a draft pull request that adds a remediation proposal to a repository.
 *
 * <p>It only ever creates a new branch of its own (named from the finding, so a retry finds it),
 * puts one Markdown file on it, and opens a <em>draft</em> pull request from it. It never pushes to
 * an existing branch, never merges, and refuses if the host says the branch it would use is
 * protected. The host client it uses has no operation that could do otherwise.
 *
 * <p>It is idempotent: if a pull request for the finding is already open it is returned and nothing
 * is created.
 */
public final class FixAgentPrCreator {

  private static final Logger LOG = LoggerFactory.getLogger(FixAgentPrCreator.class);

  private final SourceHostClient host;
  private final RunLinks runLinks;

  /**
   * Creates the creator.
   *
   * @param host the source host
   * @param runLinks where a person can look at a run
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public FixAgentPrCreator(SourceHostClient host, RunLinks runLinks) {
    this.host = Objects.requireNonNull(host, "host");
    this.runLinks = Objects.requireNonNull(runLinks, "runLinks");
  }

  /**
   * Opens the pull request.
   *
   * @param request the finding and where the pull request should point
   * @param proposal what to propose
   * @return the pull request, or why none was opened
   * @throws com.debthunter.controlplane.ai.remediation.host.SourceHostUnavailableException if the
   *     host is unreachable or rate-limiting
   * @throws com.debthunter.controlplane.ai.remediation.host.SourceHostRejectedException if the host
   *     refuses
   */
  public PrOutcome create(RemediationRequest request, RemediationProposal proposal) {
    String repository = request.repository();
    String fingerprint = request.finding().fingerprint();
    String branch = BranchNames.branchFor(repository, fingerprint);

    Optional<PullRequestRef> existing = host.findOpenPullRequest(repository, branch);
    if (existing.isPresent()) {
      return new PrOutcome.Opened(existing.get(), true);
    }

    Optional<String> baseSha = host.branchHead(repository, request.baseBranch());
    if (baseSha.isEmpty()) {
      return new PrOutcome.Refused(
          PrOutcome.Reason.BASE_BRANCH_MISSING,
          "The base branch does not exist: " + request.baseBranch());
    }
    if (host.isProtected(repository, branch)) {
      LOG.warn("Refusing to use protected branch {} in {}", branch, repository);
      return new PrOutcome.Refused(
          PrOutcome.Reason.PROTECTED_BRANCH, "The host protects the proposal branch " + branch);
    }

    host.ensureBranchWithFile(
        repository,
        branch,
        baseSha.get(),
        BranchNames.fileFor(repository, fingerprint),
        PullRequestText.document(request, proposal),
        PullRequestText.commitMessage(proposal));
    PullRequestRef opened =
        host.openDraftPullRequest(
            repository,
            branch,
            request.baseBranch(),
            PullRequestText.title(proposal, request.finding()),
            PullRequestText.body(request, proposal, runLinks.linkTo(request.runId())),
            PullRequestText.labels());
    return new PrOutcome.Opened(opened, false);
  }
}
