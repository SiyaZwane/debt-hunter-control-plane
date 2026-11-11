package com.debthunter.controlplane.ingest.lifecycle;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Decides how two commits relate by walking the Git parent-child graph, never by comparing when
 * their bundles happened to arrive.
 *
 * <p>Arrival order is a property of the network and the queue; the parent graph is a property of
 * the code. Applying a bundle for an older commit after a newer one has been applied would
 * resurrect findings the newer commit already fixed.
 */
public final class CommitTopologyResolver {

  /** How a commit relates to the commit already applied to a slice. */
  public enum Relation {
    /** It is the same commit. */
    SAME,
    /** It is an ancestor of the applied commit: it arrived late. */
    ANCESTOR,
    /** It descends from the applied commit: the normal case. */
    DESCENDANT,
    /** Both are fully known and neither descends from the other (parallel branches). */
    DIVERGENT,
    /** The graph does not know enough to say. */
    UNKNOWN
  }

  /** Bound on how far back the walk goes, so a huge or cyclic graph cannot hang ingest. */
  static final int MAX_WALK = 10_000;

  private final CommitGraph graph;

  /**
   * Creates a resolver.
   *
   * @param graph where commit parents come from
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public CommitTopologyResolver(CommitGraph graph) {
    this.graph = Objects.requireNonNull(graph, "graph");
  }

  /**
   * Relates an arriving commit to the one already applied.
   *
   * @param repository the repository
   * @param arriving the commit of the bundle that just arrived
   * @param applied the commit most recently applied to the slice
   * @return how they relate
   */
  public Relation relate(String repository, String arriving, String applied) {
    if (arriving.equals(applied)) {
      return Relation.SAME;
    }
    Walk fromApplied = reaches(repository, applied, arriving);
    if (fromApplied == Walk.FOUND) {
      return Relation.ANCESTOR;
    }
    Walk fromArriving = reaches(repository, arriving, applied);
    if (fromArriving == Walk.FOUND) {
      return Relation.DESCENDANT;
    }
    if (fromApplied == Walk.EXHAUSTED && fromArriving == Walk.EXHAUSTED) {
      return Relation.DIVERGENT;
    }
    return Relation.UNKNOWN;
  }

  private enum Walk {
    FOUND,
    EXHAUSTED,
    INCOMPLETE
  }

  /** Walks parents from {@code start} looking for {@code target}. */
  private Walk reaches(String repository, String start, String target) {
    Set<String> seen = new HashSet<>();
    Deque<String> queue = new ArrayDeque<>();
    queue.add(start);
    boolean gap = false;
    while (!queue.isEmpty()) {
      String current = queue.poll();
      if (!seen.add(current)) {
        continue;
      }
      if (seen.size() > MAX_WALK) {
        return Walk.INCOMPLETE;
      }
      if (current.equals(target)) {
        return Walk.FOUND;
      }
      var node = graph.node(repository, current);
      if (node.isEmpty()) {
        gap = true;
        continue;
      }
      queue.addAll(node.get().parents());
    }
    return gap ? Walk.INCOMPLETE : Walk.EXHAUSTED;
  }
}
