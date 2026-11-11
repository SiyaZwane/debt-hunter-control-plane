package com.debthunter.controlplane.ai.guard;

/**
 * Looks up a repository's source-sharing choice.
 *
 * <p>Failure contract: it may throw if the answer cannot be had. The guard then behaves as if the
 * repository had not opted in; an unknown answer is never read as consent.
 */
public interface SourceSharingLookup {

  /**
   * The repository's choice.
   *
   * @param repository the repository
   * @return its choice; a repository with no recorded choice has not opted in
   */
  SourceSharing of(String repository);
}
