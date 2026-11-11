package com.debthunter.controlplane.ingest.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ingest.lifecycle.CommitTopologyResolver.Relation;
import org.junit.jupiter.api.Test;

class CommitTopologyResolverTest {

  private static final String R = "acme/widgets";

  // c1 <- c2 <- c3, and a branch c2 <- b1
  private final InMemoryCommitGraph graph =
      new InMemoryCommitGraph()
          .add(R, "c1", LifecycleFixtures.day(1))
          .add(R, "c2", LifecycleFixtures.day(2), "c1")
          .add(R, "c3", LifecycleFixtures.day(3), "c2")
          .add(R, "b1", LifecycleFixtures.day(3), "c2");

  private final CommitTopologyResolver resolver = new CommitTopologyResolver(graph);

  @Test
  void theSameCommitIsSame() {
    assertThat(resolver.relate(R, "c2", "c2")).isEqualTo(Relation.SAME);
  }

  @Test
  void aCommitBehindTheAppliedOneIsAnAncestorSoItArrivedLate() {
    assertThat(resolver.relate(R, "c1", "c3")).isEqualTo(Relation.ANCESTOR);
    assertThat(resolver.relate(R, "c2", "c3")).isEqualTo(Relation.ANCESTOR);
  }

  @Test
  void aCommitAheadOfTheAppliedOneIsADescendant() {
    assertThat(resolver.relate(R, "c3", "c1")).isEqualTo(Relation.DESCENDANT);
  }

  @Test
  void commitsOnParallelBranchesAreDivergent() {
    assertThat(resolver.relate(R, "b1", "c3")).isEqualTo(Relation.DIVERGENT);
  }

  @Test
  void aCommitTheGraphDoesNotKnowIsUnknownNotGuessed() {
    assertThat(resolver.relate(R, "mystery", "c3")).isEqualTo(Relation.UNKNOWN);
  }

  @Test
  void aGapInTheHistoryMakesTheAnswerUnknownRatherThanDivergent() {
    InMemoryCommitGraph gappy =
        new InMemoryCommitGraph()
            .add(R, "c2", LifecycleFixtures.day(2), "missing-parent")
            .add(R, "c3", LifecycleFixtures.day(3), "c2");

    assertThat(new CommitTopologyResolver(gappy).relate(R, "c1", "c3")).isEqualTo(Relation.UNKNOWN);
  }

  @Test
  void aCyclicGraphCannotHangTheWalk() {
    InMemoryCommitGraph cyclic =
        new InMemoryCommitGraph()
            .add(R, "a", LifecycleFixtures.day(1), "b")
            .add(R, "b", LifecycleFixtures.day(2), "a");

    assertThat(new CommitTopologyResolver(cyclic).relate(R, "x", "a")).isNotNull();
  }

  @Test
  void anotherRepositoryHasItsOwnGraph() {
    assertThat(resolver.relate("other/repo", "c1", "c3")).isEqualTo(Relation.UNKNOWN);
  }
}
