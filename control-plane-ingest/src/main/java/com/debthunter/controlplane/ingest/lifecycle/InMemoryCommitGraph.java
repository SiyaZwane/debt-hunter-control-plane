package com.debthunter.controlplane.ingest.lifecycle;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A {@link CommitGraph} held in memory, for tests and local development. */
public final class InMemoryCommitGraph implements CommitGraph {

  private final Map<String, CommitNode> nodes = new ConcurrentHashMap<>();

  /**
   * Adds a commit.
   *
   * @param repository the repository
   * @param commit the commit id
   * @param committedAt the commit date
   * @param parents its parents' ids
   * @return this graph, for chaining
   */
  public InMemoryCommitGraph add(
      String repository, String commit, Instant committedAt, String... parents) {
    nodes.put(
        repository + '\u0000' + commit, new CommitNode(commit, List.of(parents), committedAt));
    return this;
  }

  @Override
  public Optional<CommitNode> node(String repository, String commit) {
    return Optional.ofNullable(nodes.get(repository + '\u0000' + commit));
  }
}
