package com.debthunter.controlplane.store.runs;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

abstract class RunRepositoryContract {

  private static final Instant AT = Instant.parse("2026-01-01T00:00:00Z");

  protected abstract RunRepository newRepository();

  private RunRepository runs;

  @BeforeEach
  void createRepository() {
    runs = newRepository();
  }

  private static RunRecord run(String id, String commit, String key, RunStatus status) {
    return run(id, commit, key, false, false, status);
  }

  private static RunRecord run(
      String id,
      String commit,
      String key,
      boolean degraded,
      boolean scopeLimited,
      RunStatus status) {
    return new RunRecord(
        id,
        "acme/widgets",
        "default",
        commit,
        "0.1.0",
        key,
        degraded,
        scopeLimited,
        List.of(new EngineRecord("code-maat", degraded ? "FAILED" : "OK")),
        AT,
        status,
        null);
  }

  @Test
  void aSavedRunReadsBackWithItsEngineStatusesAndDegradedFlag() {
    runs.save(run("r1", "c1", "k1", true, false, RunStatus.ACTIVE));

    assertThat(runs.find("r1"))
        .get()
        .satisfies(
            r -> {
              assertThat(r.degraded()).isTrue();
              assertThat(r.engines()).containsExactly(new EngineRecord("code-maat", "FAILED"));
            });
  }

  @Test
  void aRunIsCompleteOnlyIfNeitherDegradedNorScopeLimited() {
    assertThat(run("r", "c", "k", false, false, RunStatus.ACTIVE).isComplete()).isTrue();
    assertThat(run("r", "c", "k", true, false, RunStatus.ACTIVE).isComplete()).isFalse();
    assertThat(run("r", "c", "k", false, true, RunStatus.ACTIVE).isComplete()).isFalse();
  }

  @Test
  void savingTheSameRunTwiceIsHarmless() {
    runs.save(run("r1", "c1", "k1", RunStatus.ACTIVE));
    runs.save(run("r1", "c1", "k1", RunStatus.ACTIVE));

    assertThat(runs.findByCompositeKey("k1")).hasSize(1);
  }

  @Test
  void anActiveRunWithTheSameCompositeKeySupersedesTheEarlierOneWithoutDeletingIt() {
    runs.save(run("r1", "c1", "k1", RunStatus.ACTIVE));
    runs.save(run("r2", "c1", "k1", RunStatus.ACTIVE));

    assertThat(runs.find("r1").get().status()).isEqualTo(RunStatus.SUPERSEDED);
    assertThat(runs.find("r1").get().supersededBy()).isEqualTo("r2");
    assertThat(runs.findByCompositeKey("k1")).hasSize(2);
  }

  @Test
  void aLateRunDoesNotSupersedeAndDoesNotBecomeTheLatestApplied() {
    runs.save(run("r3", "c3", "k3", RunStatus.ACTIVE));
    runs.save(run("r1", "c1", "k3", RunStatus.LATE));

    assertThat(runs.find("r3").get().status()).isEqualTo(RunStatus.ACTIVE);
    assertThat(runs.latestApplied("acme/widgets", "default").get().runId()).isEqualTo("r3");
  }

  @Test
  void aScopeLimitedRunNeverBecomesTheLatestApplied() {
    runs.save(run("r1", "c1", "k1", RunStatus.ACTIVE));
    runs.save(run("pr", "c9", "k9", false, true, RunStatus.SCOPE_LIMITED));

    assertThat(runs.latestApplied("acme/widgets", "default").get().runId()).isEqualTo("r1");
  }

  @Test
  void aSliceWithNothingAppliedHasNoLatestRun() {
    assertThat(runs.latestApplied("acme/widgets", "default")).isEmpty();
    assertThat(runs.find("nope")).isEmpty();
  }

  @Test
  void aConsumerFollowingRunsSeesOnlyActiveOnesInTheOrderTheyWereApplied() {
    runs.save(run("r1", "c1", "k1", RunStatus.ACTIVE));
    runs.save(run("r-pr", "c9", "k9", false, true, RunStatus.SCOPE_LIMITED));
    runs.save(run("r-late", "c0", "k0", RunStatus.LATE));
    runs.save(run("r2", "c2", "k2", RunStatus.ACTIVE));

    assertThat(runs.activeAfter(0, 10))
        .extracting(r -> r.run().runId())
        .containsExactly("r1", "r2");
  }

  @Test
  void aSupersededRunDropsOutOfTheFeedAndItsReplacementTakesItsPlace() {
    runs.save(run("r1", "c1", "k1", RunStatus.ACTIVE));
    runs.save(run("r1b", "c1", "k1", RunStatus.ACTIVE));

    assertThat(runs.activeAfter(0, 10)).extracting(r -> r.run().runId()).containsExactly("r1b");
  }

  @Test
  void aConsumerCanResumeAfterAPositionAndPage() {
    runs.save(run("r1", "c1", "k1", RunStatus.ACTIVE));
    runs.save(run("r2", "c2", "k2", RunStatus.ACTIVE));
    runs.save(run("r3", "c3", "k3", RunStatus.ACTIVE));

    List<SequencedRun> first = runs.activeAfter(0, 2);
    List<SequencedRun> rest = runs.activeAfter(first.get(1).seq(), 2);

    assertThat(first).extracting(r -> r.run().runId()).containsExactly("r1", "r2");
    assertThat(rest).extracting(r -> r.run().runId()).containsExactly("r3");
    assertThat(first).extracting(SequencedRun::seq).isSorted().doesNotHaveDuplicates();
  }

  @Test
  void theRunsPolicyOutcomeIsRetainedWhenRecordedAndAbsentWhenNot() {
    runs.save(
        new RunRecord(
            "r1",
            "acme/widgets",
            "default",
            "c1",
            "0.1.0",
            "k1",
            false,
            false,
            List.of(),
            AT,
            RunStatus.ACTIVE,
            null,
            "WOULD_FAIL"));
    runs.save(run("r2", "c2", "k2", RunStatus.ACTIVE));

    assertThat(runs.find("r1").get().policyStatus()).isEqualTo("WOULD_FAIL");
    assertThat(runs.find("r2").get().policyStatus()).isNull();
  }

  @Test
  void aSupersededRunKeepsItsPolicyOutcome() {
    runs.save(
        new RunRecord(
            "r1",
            "acme/widgets",
            "default",
            "c1",
            "0.1.0",
            "k1",
            false,
            false,
            List.of(),
            AT,
            RunStatus.ACTIVE,
            null,
            "FAILED"));
    runs.save(run("r1b", "c1", "k1", RunStatus.ACTIVE));

    assertThat(runs.find("r1").get().policyStatus()).isEqualTo("FAILED");
  }
}
