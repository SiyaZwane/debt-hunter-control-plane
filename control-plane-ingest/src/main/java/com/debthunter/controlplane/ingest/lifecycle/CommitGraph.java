package com.debthunter.controlplane.ingest.lifecycle;

import java.util.Optional;

/**
 * Where the control plane learns a repository's commit graph and commit dates.
 *
 * <p>A run bundle carries its commit id but not its parents or its commit date, so this has to come
 * from somewhere else (the Git host's API, or a mirror). It is a port so that source is a decision
 * for the deployment, and so a commit that is not known simply returns empty.
 */
public interface CommitGraph {

  /**
   * Looks a commit up.
   *
   * @param repository the repository
   * @param commit the commit id
   * @return the commit, or empty if it is not known
   */
  Optional<CommitNode> node(String repository, String commit);
}
