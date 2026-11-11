package com.debthunter.controlplane.ingest.lifecycle;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * What is known about one commit.
 *
 * @param commit the commit id
 * @param parents its parents' ids (empty for a root commit)
 * @param committedAt the commit date
 */
public record CommitNode(String commit, List<String> parents, Instant committedAt) {

  /** Validates and defensively copies the components. */
  public CommitNode {
    Objects.requireNonNull(commit, "commit");
    Objects.requireNonNull(committedAt, "committedAt");
    parents = parents == null ? List.of() : List.copyOf(parents);
  }
}
