package com.debthunter.controlplane.ai.remediation.host;

import java.util.List;
import java.util.Optional;

/**
 * The part of a source host (GitHub, Azure DevOps) that remediation proposals need.
 *
 * <p>It is deliberately small. It can create a <em>new</em> branch, put one file on it and open a
 * <em>draft</em> pull request, and it can look. It has no way to merge, approve, update an existing
 * reference, force-push, delete, or change protection, so nothing built on it can bypass branch
 * protection or land a change: that is a property of the type, not of care in its callers.
 *
 * <p>Failure contract: an unreachable, slow or rate-limiting host is {@link
 * SourceHostUnavailableException}; a host that answers and refuses is {@link
 * SourceHostRejectedException}. Credentials never appear in either message.
 */
public interface SourceHostClient {

  /**
   * The commit a branch points at.
   *
   * @param repository the repository, in the form this host uses
   * @param branch the branch
   * @return the commit id, or empty if there is no such branch
   */
  Optional<String> branchHead(String repository, String branch);

  /**
   * Whether a branch is protected, whether by a protection rule or by a ruleset, including one that
   * applies to a branch that does not exist yet.
   *
   * @param repository the repository
   * @param branch the branch name
   * @return true if the host would restrict changes to it
   */
  boolean isProtected(String repository, String branch);

  /**
   * Makes sure a branch exists with one file on it. Creates the branch from {@code baseSha} if it
   * is missing, then adds the file or replaces it if it differs. Safe to repeat.
   *
   * @param repository the repository
   * @param branch the new branch's name
   * @param baseSha the commit to branch from
   * @param path where the file goes
   * @param content the file's text
   * @param commitMessage the commit message
   */
  void ensureBranchWithFile(
      String repository,
      String branch,
      String baseSha,
      String path,
      String content,
      String commitMessage);

  /**
   * The open pull request from a branch, if there is one.
   *
   * @param repository the repository
   * @param headBranch the branch the pull request comes from
   * @return the pull request, or empty
   */
  Optional<PullRequestRef> findOpenPullRequest(String repository, String headBranch);

  /**
   * Opens a draft pull request.
   *
   * @param repository the repository
   * @param headBranch the branch to merge from
   * @param baseBranch the branch it would merge into
   * @param title the title
   * @param body the description
   * @param labels labels to apply (best effort)
   * @return the pull request
   */
  PullRequestRef openDraftPullRequest(
      String repository,
      String headBranch,
      String baseBranch,
      String title,
      String body,
      List<String> labels);
}
