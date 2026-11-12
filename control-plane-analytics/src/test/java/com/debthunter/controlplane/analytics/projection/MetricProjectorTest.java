package com.debthunter.controlplane.analytics.projection;

import static com.debthunter.controlplane.analytics.Days.REPO;
import static com.debthunter.controlplane.analytics.Days.day;
import static com.debthunter.controlplane.analytics.Days.run;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.analytics.MutableClock;
import com.debthunter.controlplane.analytics.PipelineDriver;
import com.debthunter.controlplane.analytics.readmodel.InMemoryReadModelRepository;
import com.debthunter.controlplane.analytics.readmodel.ProjectionBatch;
import com.debthunter.controlplane.analytics.readmodel.ReadModelRepository;
import com.debthunter.controlplane.analytics.readmodel.RunSnapshot;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.lifecycle.FindingTransitionEvent;
import com.debthunter.controlplane.store.lifecycle.TransitionCause;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetricProjectorTest {

  private static final Instant FOREVER = Instant.parse("2100-01-01T00:00:00Z");

  private final PipelineDriver pipeline = PipelineDriver.inMemory();
  private final InMemoryReadModelRepository readModel = new InMemoryReadModelRepository();
  private final MutableClock clock = new MutableClock(day(100));

  private MetricProjector projector(ReadModelRepository model, int batch) {
    return new MetricProjector(
        pipeline.transitions, pipeline.runs, pipeline.findings, model, clock, batch);
  }

  private MetricProjector projector() {
    return projector(readModel, 100);
  }

  private List<RunSnapshot> snapshots() {
    return readModel.snapshots(REPO, "default", day(-1), FOREVER);
  }

  // ---- run snapshots ---------------------------------------------------------------------------

  @Test
  void cp_ac29_aRunBecomesASnapshotCountedBySeverity() {
    pipeline.apply(
        run(1)
            .finding("a", "r1", "CRITICAL", "STATIC_ANALYSIS", "A.java", null, null)
            .finding("b", "r1", "HIGH", "STATIC_ANALYSIS", "B.java", null, null)
            .finding("c", "r2", "HIGH", "STATIC_ANALYSIS", "C.java", null, null)
            .finding("d", "r2", "LOW", "STATIC_ANALYSIS", "D.java", null, null)
            .policy("WOULD_FAIL"));

    projector().project();

    assertThat(snapshots())
        .singleElement()
        .satisfies(
            s -> {
              assertThat(s.critical()).isEqualTo(1);
              assertThat(s.high()).isEqualTo(2);
              assertThat(s.low()).isEqualTo(1);
              assertThat(s.medium()).isZero();
              assertThat(s.total()).isEqualTo(4);
              assertThat(s.policyStatus()).isEqualTo("WOULD_FAIL");
              assertThat(s.committedAt()).isEqualTo(day(1));
            });
  }

  @Test
  void architectureViolationsAndComplexityAreSummedPerRun() {
    pipeline.apply(
        run(1)
            .finding("a", "arch", "HIGH", "ARCHITECTURE", "A.java", null, 3.0)
            .finding("b", "arch", "HIGH", "ARCHITECTURE", "B.java", null, -1.0)
            .finding("c", "other", "LOW", "STATIC_ANALYSIS", "C.java", null, 2.5));

    projector().project();

    assertThat(snapshots().get(0).architectureViolations()).isEqualTo(2);
    assertThat(snapshots().get(0).complexityDelta()).isEqualTo(4.5);
  }

  @Test
  void onlyHotspotFindingsWithAChangeFrequencyBecomeHotspotPoints() {
    pipeline.apply(
        run(1)
            .finding("a", "hot", "HIGH", "HOTSPOT", "Hot.java", 9.0, null)
            .finding("b", "hot", "HIGH", "HOTSPOT", "NoFreq.java", null, null)
            .finding("c", "other", "HIGH", "STATIC_ANALYSIS", "Other.java", 7.0, null));

    projector().project();

    assertThat(readModel.hotspots(REPO, "default", day(-1), FOREVER))
        .singleElement()
        .satisfies(
            h -> {
              assertThat(h.path()).isEqualTo("Hot.java");
              assertThat(h.changeFrequency()).isEqualTo(9.0);
            });
  }

  @Test
  void aPullRequestRunIsNotPartOfTheMainHistoryAndIsNotProjected() {
    pipeline.apply(run(1).observing("a"));
    pipeline.apply(run(2).pullRequest("c1", "7").observing("a", "b", "c"));

    projector().project();

    assertThat(snapshots()).extracting(RunSnapshot::commit).containsExactly("c1");
  }

  @Test
  void aRerunReplacesTheSnapshotAndHotspotsOfTheSameCommit() {
    pipeline.apply(
        run(1).id("first").finding("a", "hot", "HIGH", "HOTSPOT", "Old.java", 5.0, null));
    projector().project();
    pipeline.apply(
        run(1).id("second").finding("a", "hot", "HIGH", "HOTSPOT", "New.java", 6.0, null));

    projector().project();

    assertThat(snapshots()).hasSize(1);
    assertThat(snapshots().get(0).runId()).isEqualTo("second");
    assertThat(readModel.hotspots(REPO, "default", day(-1), FOREVER))
        .extracting(h -> h.path())
        .containsExactly("New.java");
  }

  // ---- transitions ----------------------------------------------------------------------------

  @Test
  void aFindingOpensWhenCreatedAndEntersTheOpenListWithItsRuleAndSeverity() {
    pipeline.apply(run(1).finding("a", "rule-x", "HIGH", "STATIC_ANALYSIS", "A.java", null, null));

    projector().project();

    assertThat(readModel.openFindings(REPO, "default"))
        .singleElement()
        .satisfies(
            o -> {
              assertThat(o.ruleId()).isEqualTo("rule-x");
              assertThat(o.severity()).isEqualTo("HIGH");
              assertThat(o.category()).isEqualTo("STATIC_ANALYSIS");
              assertThat(o.since()).isEqualTo(day(1));
            });
    assertThat(readModel.ruleDeltas(REPO, "default", day(0), day(5)))
        .singleElement()
        .satisfies(d -> assertThat(d.created()).isEqualTo(1));
  }

  @Test
  void resolvingAFindingRecordsHowLongItWasOpenAndClosesIt() {
    pipeline.apply(run(1).finding("a", "rule-x", "HIGH", "STATIC_ANALYSIS", "A.java", null, null));
    pipeline.apply(run(4));

    projector().project();

    assertThat(readModel.openFindings(REPO, "default")).isEmpty();
    assertThat(readModel.resolutions(REPO, "default", day(0), FOREVER))
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.durationSeconds()).isEqualTo(Duration.ofDays(3).toSeconds());
              assertThat(r.ruleId()).isEqualTo("rule-x");
              assertThat(r.resolvedAt()).isEqualTo(day(4));
            });
  }

  @Test
  void aFindingThatReturnsIsOpenAgainFromWhenItReturned() {
    pipeline.apply(run(1).finding("a", "rule-x", "HIGH", "STATIC_ANALYSIS", "A.java", null, null));
    pipeline.apply(run(2));
    pipeline.apply(run(5).finding("a", "rule-x", "HIGH", "STATIC_ANALYSIS", "A.java", null, null));

    projector().project();

    assertThat(readModel.openFindings(REPO, "default"))
        .singleElement()
        .satisfies(o -> assertThat(o.since()).isEqualTo(day(5)));
    var deltas = readModel.ruleDeltas(REPO, "default", day(0), day(10));
    assertThat(deltas.stream().mapToInt(d -> d.created()).sum()).isEqualTo(1);
    assertThat(deltas.stream().mapToInt(d -> d.resolved()).sum()).isEqualTo(1);
    assertThat(deltas.stream().mapToInt(d -> d.regressed()).sum()).isEqualTo(1);
  }

  @Test
  void aResolutionWithNoRecordedStartIsStillCountedButHasNoDuration() {
    pipeline.transitions.append(
        List.of(
            new FindingTransitionEvent(
                "e1",
                REPO,
                "default",
                "ghost",
                FindingState.OPEN,
                FindingState.RESOLVED,
                "r",
                day(3),
                TransitionCause.ABSENT_FROM_COMPLETE_RUN)));

    projector().project();

    assertThat(readModel.resolutions(REPO, "default", day(0), FOREVER)).isEmpty();
    assertThat(readModel.ruleDeltas(REPO, "default", day(0), day(10)))
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.ruleId()).isEqualTo("unknown");
              assertThat(d.resolved()).isEqualTo(1);
            });
  }

  // ---- progress, restart and failure -----------------------------------------------------------

  @Test
  void aSecondPassWithNothingNewProjectsNothingAndDoesNotDoubleCount() {
    pipeline.apply(run(1).observing("a"));
    pipeline.apply(run(2));
    MetricProjector projector = projector();

    int first = projector.project();
    int second = projector.project();

    assertThat(first).isPositive();
    assertThat(second).isZero();
    assertThat(readModel.ruleDeltas(REPO, "default", day(0), day(10)).get(0).created())
        .isEqualTo(1);
  }

  @Test
  void aNewProjectorResumesFromTheCheckpointInsteadOfStartingOver() {
    pipeline.apply(run(1).observing("a"));
    projector().project();
    pipeline.apply(run(2));

    int resumed = projector().project();

    assertThat(resumed).isEqualTo(2); // one new run, one new transition
    assertThat(readModel.resolutions(REPO, "default", day(0), FOREVER)).hasSize(1);
  }

  @Test
  void smallBatchesGiveExactlyTheSameResultsAsOneBigBatch() {
    for (int d = 1; d <= 6; d++) {
      pipeline.apply(d % 3 == 0 ? run(d) : run(d).observing("a", "b"));
    }
    InMemoryReadModelRepository big = new InMemoryReadModelRepository();
    InMemoryReadModelRepository small = new InMemoryReadModelRepository();

    projector(big, 1000).project();
    projector(small, 1).project();

    assertThat(small.snapshots(REPO, "default", day(-1), FOREVER))
        .isEqualTo(big.snapshots(REPO, "default", day(-1), FOREVER));
    assertThat(small.ruleDeltas(REPO, "default", day(-1), FOREVER))
        .isEqualTo(big.ruleDeltas(REPO, "default", day(-1), FOREVER));
    assertThat(small.resolutions(REPO, "default", day(-1), FOREVER))
        .isEqualTo(big.resolutions(REPO, "default", day(-1), FOREVER));
    assertThat(small.openFindings(REPO, "default")).isEqualTo(big.openFindings(REPO, "default"));
  }

  @Test
  void aBatchThatFailsToCommitLeavesNothingBehindAndTheRetryCountsOnce() {
    pipeline.apply(run(1).observing("a"));
    pipeline.apply(run(2));
    boolean[] fail = {true};
    ReadModelRepository flaky =
        new DelegatingReadModel(readModel) {
          @Override
          public void commit(ProjectionBatch batch, Instant now) {
            if (fail[0] && !batch.ruleDeltas().isEmpty()) {
              throw new IllegalStateException("read model store down");
            }
            super.commit(batch, now);
          }
        };
    MetricProjector projector = projector(flaky, 100);

    assertThatThrownBy(projector::project).isInstanceOf(IllegalStateException.class);
    assertThat(readModel.checkpoint(MetricProjector.TRANSITIONS)).isZero();
    assertThat(readModel.ruleDeltas(REPO, "default", day(0), day(10))).isEmpty();

    fail[0] = false;
    projector.project();

    assertThat(readModel.ruleDeltas(REPO, "default", day(0), day(10)).get(0).created())
        .isEqualTo(1);
    assertThat(readModel.resolutions(REPO, "default", day(0), FOREVER)).hasSize(1);
  }

  @Test
  void aReadModelThatDoesNotPersistTheCheckpointIsRefusedRatherThanDoubleCounted() {
    pipeline.apply(run(1).observing("a"));
    ReadModelRepository forgetful =
        new DelegatingReadModel(readModel) {
          @Override
          public void commit(ProjectionBatch batch, Instant now) {
            // Applies the data but loses the checkpoint, so the batch would be replayed next time.
            super.commit(
                new ProjectionBatch(
                    java.util.Map.of(),
                    batch.ruleDeltas(),
                    batch.snapshots(),
                    batch.hotspots(),
                    batch.closed(),
                    batch.opened(),
                    batch.resolutions()),
                now);
          }
        };

    assertThatThrownBy(() -> projector(forgetful, 100).project())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("did not advance");
  }

  @Test
  void everyCompletedPassRecordsWhenItFinishedEvenIfNothingWasNew() {
    clock.set(day(50));
    projector().project();
    assertThat(readModel.lastUpdated()).contains(day(50));

    clock.set(day(51));
    projector().project();

    assertThat(readModel.lastUpdated()).contains(day(51));
  }

  @Test
  void aNonPositiveBatchSizeIsRefused() {
    assertThatThrownBy(() -> projector(readModel, 0)).isInstanceOf(IllegalArgumentException.class);
  }

  /** Forwards everything; a test overrides the one method it wants to break. */
  private static class DelegatingReadModel implements ReadModelRepository {
    private final ReadModelRepository delegate;

    DelegatingReadModel(ReadModelRepository delegate) {
      this.delegate = delegate;
    }

    @Override
    public long checkpoint(String name) {
      return delegate.checkpoint(name);
    }

    @Override
    public java.util.Optional<Instant> lastUpdated() {
      return delegate.lastUpdated();
    }

    @Override
    public java.util.Optional<com.debthunter.controlplane.analytics.readmodel.OpenFinding>
        openFinding(String r, String s, String f) {
      return delegate.openFinding(r, s, f);
    }

    @Override
    public void commit(ProjectionBatch batch, Instant now) {
      delegate.commit(batch, now);
    }

    @Override
    public List<RunSnapshot> latestSnapshots(java.util.Collection<String> repositories) {
      return delegate.latestSnapshots(repositories);
    }

    @Override
    public List<RunSnapshot> snapshots(String r, String s, Instant a, Instant b) {
      return delegate.snapshots(r, s, a, b);
    }

    @Override
    public List<com.debthunter.controlplane.analytics.readmodel.RuleDelta> ruleDeltas(
        String r, String s, Instant a, Instant b) {
      return delegate.ruleDeltas(r, s, a, b);
    }

    @Override
    public List<com.debthunter.controlplane.analytics.readmodel.ResolutionRecord> resolutions(
        String r, String s, Instant a, Instant b) {
      return delegate.resolutions(r, s, a, b);
    }

    @Override
    public List<com.debthunter.controlplane.analytics.readmodel.OpenFinding> openFindings(
        String r, String s) {
      return delegate.openFindings(r, s);
    }

    @Override
    public List<com.debthunter.controlplane.analytics.readmodel.HotspotPoint> hotspots(
        String r, String s, Instant a, Instant b) {
      return delegate.hotspots(r, s, a, b);
    }
  }
}
