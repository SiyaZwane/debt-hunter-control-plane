package com.debthunter.controlplane.analytics.readmodel;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

abstract class ReadModelRepositoryContract {

  static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
  static final Instant LATER = Instant.parse("2100-01-01T00:00:00Z");
  static final String R = "acme/widgets";
  static final String S = "default";

  protected abstract ReadModelRepository newRepository();

  ReadModelRepository repo;

  @BeforeEach
  void create() {
    repo = newRepository();
  }

  static Instant day(int n) {
    return T0.plus(Duration.ofDays(n));
  }

  static ProjectionBatch batch(
      Map<String, Long> checkpoints,
      List<RuleDelta> deltas,
      List<RunSnapshot> snapshots,
      List<HotspotPoint> hotspots,
      List<OpenFinding> closed,
      List<OpenFinding> opened,
      List<ResolutionRecord> resolutions) {
    return new ProjectionBatch(
        checkpoints, deltas, snapshots, hotspots, closed, opened, resolutions);
  }

  static RunSnapshot snapshot(String commit, int dayN, int high) {
    return new RunSnapshot(
        R, S, commit, "run-" + commit, day(dayN), 0, high, 0, 0, 0, 1, 2.5, "PASSED");
  }

  static OpenFinding open(String fp, int dayN) {
    return new OpenFinding(R, S, fp, "rule", "HIGH", "ARCHITECTURE", day(dayN));
  }

  @Test
  void aNewRepositoryHasNoCheckpointsAndHasNeverBeenUpdated() {
    assertThat(repo.checkpoint("runs")).isZero();
    assertThat(repo.lastUpdated()).isEmpty();
  }

  @Test
  void aCommitAdvancesTheCheckpointsItNamesAndRecordsWhenItCompleted() {
    repo.commit(
        batch(
            Map.of("runs", 7L, "transitions", 3L),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of()),
        day(5));

    assertThat(repo.checkpoint("runs")).isEqualTo(7);
    assertThat(repo.checkpoint("transitions")).isEqualTo(3);
    assertThat(repo.lastUpdated()).contains(day(5));
  }

  @Test
  void anEmptyCommitStillRefreshesTheHeartbeat() {
    repo.commit(ProjectionBatch.empty(), day(1));
    repo.commit(ProjectionBatch.empty(), day(2));

    assertThat(repo.lastUpdated()).contains(day(2));
  }

  @Test
  void ruleCountsAreAddedToWhatIsAlreadyThereNotReplaced() {
    RuleDelta d1 = new RuleDelta(R, S, "rule", LocalDate.of(2026, 1, 2), 2, 0, 0);
    RuleDelta d2 = new RuleDelta(R, S, "rule", LocalDate.of(2026, 1, 2), 1, 3, 1);
    repo.commit(
        batch(Map.of(), List.of(d1), List.of(), List.of(), List.of(), List.of(), List.of()),
        day(1));
    repo.commit(
        batch(Map.of(), List.of(d2), List.of(), List.of(), List.of(), List.of(), List.of()),
        day(2));

    assertThat(repo.ruleDeltas(R, S, day(0), LATER))
        .singleElement()
        .satisfies(
            d -> {
              assertThat(d.created()).isEqualTo(3);
              assertThat(d.resolved()).isEqualTo(3);
              assertThat(d.regressed()).isEqualTo(1);
            });
  }

  @Test
  void aSnapshotForTheSameCommitReplacesTheEarlierOneAndItsHotspots() {
    repo.commit(
        batch(
            Map.of(),
            List.of(),
            List.of(snapshot("c1", 1, 5)),
            List.of(new HotspotPoint(R, S, "c1", "Old.java", day(1), 3.0)),
            List.of(),
            List.of(),
            List.of()),
        day(1));
    repo.commit(
        batch(
            Map.of(),
            List.of(),
            List.of(snapshot("c1", 1, 9)),
            List.of(new HotspotPoint(R, S, "c1", "New.java", day(1), 4.0)),
            List.of(),
            List.of(),
            List.of()),
        day(2));

    assertThat(repo.snapshots(R, S, day(0), LATER))
        .singleElement()
        .satisfies(s -> assertThat(s.high()).isEqualTo(9));
    assertThat(repo.hotspots(R, S, day(0), LATER))
        .extracting(HotspotPoint::path)
        .containsExactly("New.java");
  }

  @Test
  void closingAnOpenFindingRemovesItAndReopeningItLaterRestoresIt() {
    repo.commit(
        batch(
            Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(open("a", 1)), List.of()),
        day(1));
    assertThat(repo.openFinding(R, S, "a")).isPresent();

    repo.commit(
        batch(
            Map.of(), List.of(), List.of(), List.of(), List.of(open("a", 1)), List.of(), List.of()),
        day(2));
    assertThat(repo.openFinding(R, S, "a")).isEmpty();

    repo.commit(
        batch(
            Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(open("a", 5)), List.of()),
        day(3));
    assertThat(repo.openFinding(R, S, "a"))
        .get()
        .satisfies(f -> assertThat(f.since()).isEqualTo(day(5)));
  }

  @Test
  void closedAndOpenedInOneBatchLeavesTheNewlyOpenedOne() {
    repo.commit(
        batch(
            Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(open("a", 1)), List.of()),
        day(1));

    repo.commit(
        batch(
            Map.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of(open("a", 1)),
            List.of(open("a", 7)),
            List.of()),
        day(2));

    assertThat(repo.openFindings(R, S))
        .singleElement()
        .satisfies(f -> assertThat(f.since()).isEqualTo(day(7)));
  }

  @Test
  void aResolutionRecordedTwiceIsStoredOnce() {
    ResolutionRecord r =
        new ResolutionRecord(R, S, "a", "rule", "HIGH", "ARCHITECTURE", day(4), 3600);
    repo.commit(
        batch(Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(r)), day(4));
    repo.commit(
        batch(Map.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(r)), day(5));

    assertThat(repo.resolutions(R, S, day(0), LATER)).hasSize(1);
  }

  @Test
  void windowsAreHalfOpenAndPerRepositoryAndSlice() {
    repo.commit(
        batch(
            Map.of(),
            List.of(),
            List.of(snapshot("c1", 1, 1), snapshot("c2", 2, 1)),
            List.of(),
            List.of(),
            List.of(),
            List.of()),
        day(3));

    assertThat(repo.snapshots(R, S, day(1), day(2)))
        .extracting(RunSnapshot::commit)
        .containsExactly("c1");
    assertThat(repo.snapshots(R, S, day(1), day(3))).hasSize(2);
    assertThat(repo.snapshots("other/repo", S, day(0), LATER)).isEmpty();
    assertThat(repo.snapshots(R, "other-slice", day(0), LATER)).isEmpty();
  }

  @Test
  void theLatestSnapshotOfEachSliceOfTheWantedRepositoriesIsFoundAndNothingElse() {
    repo.commit(
        batch(
            Map.of(),
            List.of(),
            List.of(snapshot("c1", 1, 1), snapshot("c2", 2, 5)),
            List.of(),
            List.of(),
            List.of(),
            List.of()),
        day(3));

    assertThat(repo.latestSnapshots(List.of(R)))
        .extracting(RunSnapshot::commit)
        .containsExactly("c2");
    assertThat(repo.latestSnapshots(List.of("other/repo"))).isEmpty();
    assertThat(repo.latestSnapshots(List.of())).isEmpty();
    assertThat(repo.latestSnapshots(List.of(R, "other/repo"))).hasSize(1);
  }

  @Test
  void ruleCountsAreBucketedByWholeUtcDays() {
    RuleDelta d = new RuleDelta(R, S, "rule", LocalDate.of(2026, 1, 3), 1, 0, 0);
    repo.commit(
        batch(Map.of(), List.of(d), List.of(), List.of(), List.of(), List.of(), List.of()), day(5));

    assertThat(repo.ruleDeltas(R, S, day(2), day(3))).hasSize(1); // day 2 is Jan 3
    assertThat(repo.ruleDeltas(R, S, day(3), day(9))).isEmpty();
    assertThat(repo.ruleDeltas(R, S, day(0), day(2))).isEmpty();
  }
}
