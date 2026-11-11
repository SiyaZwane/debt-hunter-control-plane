package com.debthunter.controlplane.governance.simulation;

import static com.debthunter.controlplane.governance.policy.PolicyYaml.policy;
import static com.debthunter.controlplane.governance.simulation.SimFixture.completed;
import static com.debthunter.controlplane.governance.simulation.SimFixture.finding;
import static com.debthunter.controlplane.governance.simulation.SimFixture.notSimulated;
import static com.debthunter.controlplane.governance.simulation.SimFixture.simulated;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import com.debthunter.controlplane.governance.policy.PolicyIssue;
import com.debthunter.controlplane.store.runs.RunStatus;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

class PolicyImpactSimulatorTest {

  private static final String CURRENT = policy("current").main("no-critical", "CRITICAL", 0).text();

  private SimFixture estate() {
    return new SimFixture().currentPolicy("current", CURRENT);
  }

  // ---- the verdict -----------------------------------------------------------------------------

  @Test
  void aRepositoryThatBreaksTheCandidateFailsAndOneThatDoesNotPasses() {
    SimFixture f =
        estate()
            .onboard("acme/dirty", "tier-1", "enforce", "current")
            .onboard("acme/clean", "tier-1", "enforce", "current")
            .run("acme/dirty", "d1", 1, finding("h1", Severity.HIGH))
            .run("acme/clean", "c1", 1, finding("m1", Severity.MEDIUM));

    ImpactReport report = f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report();

    assertThat(simulated(report, "acme/dirty").candidatePasses()).isFalse();
    assertThat(simulated(report, "acme/clean").candidatePasses()).isTrue();
    assertThat(report.candidateVersion()).isEqualTo("cand");
  }

  @Test
  void theVerdictIsAboutTheLatestRunNotAnOlderOne() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run("acme/a", "old", 20, finding("h1", Severity.HIGH))
            .run("acme/a", "new", 2);

    RepositoryImpact.Simulated impact =
        simulated(f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report(), "acme/a");

    assertThat(impact.evaluatedRunId()).isEqualTo("new");
    assertThat(impact.candidatePasses()).isTrue();
    assertThat(impact.runsEvaluated()).isEqualTo(2);
    assertThat(impact.runsFailingUnderCandidate()).isEqualTo(1);
  }

  @Test
  void everyViolatedRuleIsReportedNotJustTheFirst() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run(
                "acme/a",
                "r1",
                1,
                finding("c1", Severity.CRITICAL),
                finding("h1", Severity.HIGH),
                finding("h2", Severity.HIGH));

    RepositoryImpact.Simulated impact =
        simulated(
            f.simulate(
                    policy("cand")
                        .main("no-critical", "CRITICAL", 0)
                        .main("few-high", "HIGH", 1)
                        .text())
                .report(),
            "acme/a");

    assertThat(impact.candidateViolations())
        .extracting(v -> v.rule())
        .containsExactlyInAnyOrder("no-critical", "few-high");
  }

  @Test
  void findingsThatAreNotNewNeverCountTowardAThreshold() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run(
                "acme/a",
                "r1",
                1,
                finding("h1", Severity.HIGH, Category.HOTSPOT, "src/h1.java", false));

    RepositoryImpact.Simulated impact =
        simulated(f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report(), "acme/a");

    assertThat(impact.candidatePasses()).as("an inherited finding is not this run's debt").isTrue();
  }

  @Test
  void theCandidatesExclusionsApply() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run(
                "acme/a",
                "r1",
                1,
                finding("g1", Severity.HIGH, Category.HOTSPOT, "src/generated/Gen.java", true),
                finding("h2", Severity.HIGH, Category.CHURN, "src/Churny.java", true));

    RepositoryImpact.Simulated impact =
        simulated(
            f.simulate(
                    policy("cand")
                        .main("no-high", "HIGH", 0)
                        .excludePath("src/generated")
                        .excludeCategory("CHURN")
                        .text())
                .report(),
            "acme/a");

    assertThat(impact.candidatePasses()).isTrue();
  }

  @Test
  void aFindingUnderAnActiveSuppressionDoesNotCount() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run("acme/a", "r1", 1, finding("h1", Severity.HIGH));
    f.suppressions.suppress("acme/a", "fp-h1", SimFixture.NOW.plus(Duration.ofDays(30)));

    RepositoryImpact.Simulated impact =
        simulated(f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report(), "acme/a");

    assertThat(impact.candidatePasses()).isTrue();
  }

  @Test
  void aSuppressionThatHadExpiredByTheRunsCommitDateDoesNotCount() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run("acme/a", "r1", 10, finding("h1", Severity.HIGH));
    f.suppressions.suppress("acme/a", "fp-h1", SimFixture.ago(15));

    RepositoryImpact.Simulated impact =
        simulated(f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report(), "acme/a");

    assertThat(impact.candidatePasses())
        .as("the suppression expired before this run, so the finding counted then")
        .isFalse();
  }

  // ---- the delta -------------------------------------------------------------------------------

  @Test
  void aTighterCandidateNewlyFailsARepositoryAndNamesTheFindingsThatDoIt() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run(
                "acme/a",
                "r1",
                1,
                finding("h1", Severity.HIGH, Category.HOTSPOT, "src/a/H1.java", true),
                finding("h2", Severity.HIGH, Category.HOTSPOT, "src/a/H2.java", true),
                finding("m1", Severity.MEDIUM));

    RepositoryImpact.Simulated impact =
        simulated(f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report(), "acme/a");

    assertThat(impact.delta()).isEqualTo(Delta.NEWLY_FAILING);
    assertThat(impact.newlyBlocking())
        .extracting(BlockingFinding::findingId)
        .containsExactly("h1", "h2");
    assertThat(impact.newlyBlocking().get(0).blockedBy()).containsExactly("no-high");
    assertThat(impact.newlyBlocking().get(0).path()).isEqualTo("src/a/H1.java");
    assertThat(impact.newlyBlocking().get(0).severity()).isEqualTo(Severity.HIGH);
  }

  @Test
  void aFindingTheCurrentPolicyAlreadyBlocksIsNotNewlyBlocking() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run("acme/a", "r1", 1, finding("c1", Severity.CRITICAL), finding("h1", Severity.HIGH));

    RepositoryImpact.Simulated impact =
        simulated(f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report(), "acme/a");

    assertThat(impact.delta()).isEqualTo(Delta.UNCHANGED_FAILING);
    // c1 is a CRITICAL, which is also HIGH-or-worse, so the candidate counts both; the current
    // policy already blocks c1, so only h1 is new.
    assertThat(impact.newlyBlocking()).extracting(BlockingFinding::findingId).containsExactly("h1");
  }

  @Test
  void aLooserCandidateNewlyPassesARepository() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run("acme/a", "r1", 1, finding("c1", Severity.CRITICAL));

    RepositoryImpact.Simulated impact =
        simulated(
            f.simulate(policy("cand").main("few-critical", "CRITICAL", 5).text()).report(),
            "acme/a");

    assertThat(impact.delta()).isEqualTo(Delta.NEWLY_PASSING);
    assertThat(impact.newlyBlocking()).isEmpty();
  }

  @Test
  void passingUnderBothIsUnchangedPassing() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run("acme/a", "r1", 1, finding("m1", Severity.MEDIUM));

    RepositoryImpact.Simulated impact =
        simulated(f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report(), "acme/a");

    assertThat(impact.delta()).isEqualTo(Delta.UNCHANGED_PASSING);
  }

  @Test
  void whenTheCurrentPolicysContentIsUnavailableTheCandidateIsStillJudgedButNothingIsCalledNew() {
    SimFixture f =
        new SimFixture() // no current policy registered
            .onboard("acme/a", "tier-1", "enforce", "unpublished-v7")
            .run("acme/a", "r1", 1, finding("h1", Severity.HIGH));

    RepositoryImpact.Simulated impact =
        simulated(f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report(), "acme/a");

    assertThat(impact.candidatePasses()).isFalse();
    assertThat(impact.delta()).isEqualTo(Delta.UNKNOWN);
    assertThat(impact.newlyBlocking()).isEmpty();
    assertThat(impact.runsFailingUnderCurrent()).isNull();
  }

  @Test
  void aCurrentPolicyThatCannotBeLoadedIsTreatedAsUnavailableNotAsAFailure() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run("acme/a", "r1", 1, finding("h1", Severity.HIGH));
    PolicyBundleSource broken =
        version -> {
          throw new IllegalStateException("registry down");
        };
    PolicyImpactSimulator simulator =
        new PolicyImpactSimulator(
            new StoreBackedRetainedEvidence(f.registry, f.runs, f.findings, f.suppressions),
            broken,
            new com.debthunter.controlplane.governance.policy.PolicyBundleValidator(),
            f.clock);

    ImpactReport report =
        completed(
                simulator.simulate(
                    policy("cand").main("no-high", "HIGH", 0).text(), SimulationRequest.defaults()))
            .report();

    assertThat(simulated(report, "acme/a").delta()).isEqualTo(Delta.UNKNOWN);
  }

  @Test
  void runsThatTheCurrentPolicyWouldHaveBlockedAreCountedForComparison() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run("acme/a", "r1", 30, finding("c1", Severity.CRITICAL))
            .run("acme/a", "r2", 20, finding("h1", Severity.HIGH))
            .run("acme/a", "r3", 10, finding("h2", Severity.HIGH));

    RepositoryImpact.Simulated impact =
        simulated(f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report(), "acme/a");

    assertThat(impact.runsEvaluated()).isEqualTo(3);
    assertThat(impact.runsFailingUnderCandidate()).isEqualTo(3);
    assertThat(impact.runsFailingUnderCurrent()).isEqualTo(1);
  }

  // ---- which runs are replayed -----------------------------------------------------------------

  @Test
  void onlyRunsInsideTheLookbackWindowAreReplayed() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run("acme/a", "ancient", 200, finding("h1", Severity.HIGH))
            .run("acme/a", "recent", 5);

    RepositoryImpact.Simulated impact =
        simulated(f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report(), "acme/a");

    assertThat(impact.runsEvaluated()).isEqualTo(1);
    assertThat(impact.runsFailingUnderCandidate()).isZero();
  }

  @Test
  void theDefaultLookbackIsNinetyDays() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run("acme/a", "inside", 89)
            .run("acme/a", "outside", 91);

    RepositoryImpact.Simulated impact =
        simulated(f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report(), "acme/a");

    assertThat(impact.runsEvaluated()).isEqualTo(1);
    assertThat(SimulationRequest.defaults().lookback()).isEqualTo(Duration.ofDays(90));
  }

  @Test
  void aLongerLookbackReachesFurtherBack() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run("acme/a", "old", 150)
            .run("acme/a", "new", 5);

    ImpactReport report =
        completed(
                f.simulator()
                    .simulate(
                        policy("cand").main("no-high", "HIGH", 0).text(),
                        SimulationRequest.over(Duration.ofDays(180))))
            .report();

    assertThat(simulated(report, "acme/a").runsEvaluated()).isEqualTo(2);
    assertThat(report.lookback()).isEqualTo(Duration.ofDays(180));
  }

  @Test
  void aRunInTheFutureIsNotReplayed() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run("acme/a", "real", 3)
            .run("acme/a", "future", -2, finding("h1", Severity.HIGH));

    RepositoryImpact.Simulated impact =
        simulated(f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report(), "acme/a");

    assertThat(impact.evaluatedRunId()).isEqualTo("real");
  }

  @Test
  void degradedScopeLimitedSupersededAndLateRunsAreNotReplayedBecauseTheyUnderstateTheDebt() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run("acme/a", "good", 10)
            // These are newer than "good" and carry findings, but none is a complete, applied run.
            .run(
                "acme/a",
                "degraded",
                1,
                "FULL",
                RunStatus.ACTIVE,
                true,
                false,
                finding("h1", Severity.HIGH))
            .run(
                "acme/a",
                "pr",
                2,
                "FULL",
                RunStatus.SCOPE_LIMITED,
                false,
                true,
                finding("h2", Severity.HIGH))
            .run(
                "acme/a",
                "late",
                3,
                "FULL",
                RunStatus.LATE,
                false,
                false,
                finding("h3", Severity.HIGH))
            .run(
                "acme/a",
                "superseded",
                4,
                "FULL",
                RunStatus.SUPERSEDED,
                false,
                false,
                finding("h4", Severity.HIGH));

    RepositoryImpact.Simulated impact =
        simulated(f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report(), "acme/a");

    assertThat(impact.evaluatedRunId()).isEqualTo("good");
    assertThat(impact.runsEvaluated()).isEqualTo(1);
    assertThat(impact.candidatePasses()).isTrue();
  }

  @Test
  void theNumberOfRunsReplayedIsBoundedAndTheTruncationIsSaid() {
    SimFixture f = estate().onboard("acme/a", "tier-1", "enforce", "current");
    for (int i = 0; i < 12; i++) {
      f.run("acme/a", "r" + i, i + 1);
    }

    ImpactReport report =
        completed(
                f.simulator()
                    .simulate(
                        policy("cand").main("no-high", "HIGH", 0).text(),
                        new SimulationRequest(Duration.ofDays(90), 5)))
            .report();

    RepositoryImpact.Simulated impact = simulated(report, "acme/a");
    assertThat(impact.runsEvaluated()).isEqualTo(5);
    assertThat(impact.windowTruncated()).isTrue();
    assertThat(impact.evaluatedRunId()).isEqualTo("r0");
  }

  @Test
  void aWindowThatFitsIsNotMarkedTruncated() {
    SimFixture f =
        estate().onboard("acme/a", "tier-1", "enforce", "current").run("acme/a", "r1", 1);

    assertThat(
            simulated(
                    f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report(), "acme/a")
                .windowTruncated())
        .isFalse();
  }

  // ---- history depth ---------------------------------------------------------------------------

  @Test
  void aShallowRunFailsACandidateThatRequiresFullHistory() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run("acme/a", "r1", 1, "SHALLOW", RunStatus.ACTIVE, false, false);

    RepositoryImpact.Simulated impact =
        simulated(
            f.simulate(policy("cand").main("no-high", "HIGH", 0).requireHistory("FULL").text())
                .report(),
            "acme/a");

    assertThat(impact.candidatePasses()).isFalse();
    assertThat(impact.candidateViolations())
        .extracting(v -> v.rule())
        .containsExactly("minimum-history-depth");
    assertThat(impact.delta()).isEqualTo(Delta.NEWLY_FAILING);
    assertThat(impact.newlyBlocking()).as("no finding is to blame").isEmpty();
  }

  @Test
  void aDeepEnoughRunPassesTheHistoryRequirement() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run("acme/a", "r1", 1, "FULL", RunStatus.ACTIVE, false, false)
            .onboard("acme/b", "tier-1", "enforce", "current")
            .run("acme/b", "r2", 1, "PARTIAL", RunStatus.ACTIVE, false, false);

    ImpactReport report =
        f.simulate(policy("cand").main("no-high", "HIGH", 0).requireHistory("PARTIAL").text())
            .report();

    assertThat(simulated(report, "acme/a").candidatePasses()).isTrue();
    assertThat(simulated(report, "acme/b").candidatePasses()).isTrue();
  }

  @Test
  void aRunThatNeverRecordedItsHistoryDepthIsNotGuessedAtWhenTheCandidateNeedsIt() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run("acme/a", "r1", 1, null, RunStatus.ACTIVE, false, false);

    ImpactReport report =
        f.simulate(policy("cand").main("no-high", "HIGH", 0).requireHistory("FULL").text())
            .report();

    assertThat(notSimulated(report, "acme/a").reason())
        .isEqualTo(NotSimulatedReason.HISTORY_DEPTH_UNKNOWN);
  }

  @Test
  void anUnknownHistoryDepthDoesNotHideARealThresholdBreach() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run(
                "acme/a",
                "r1",
                1,
                null,
                RunStatus.ACTIVE,
                false,
                false,
                finding("h1", Severity.HIGH));

    RepositoryImpact.Simulated impact =
        simulated(
            f.simulate(policy("cand").main("no-high", "HIGH", 0).requireHistory("FULL").text())
                .report(),
            "acme/a");

    assertThat(impact.candidatePasses()).as("it fails on the finding whatever the depth").isFalse();
  }

  @Test
  void aCandidateWithNoHistoryRequirementDoesNotNeedTheRunsDepth() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run("acme/a", "r1", 1, null, RunStatus.ACTIVE, false, false);

    ImpactReport report = f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report();

    assertThat(simulated(report, "acme/a").candidatePasses()).isTrue();
  }

  @Test
  void olderRunsWithUnknownDepthAreCountedAsUndeterminableNotAsPasses() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run("acme/a", "old", 20, null, RunStatus.ACTIVE, false, false)
            .run("acme/a", "new", 2, "FULL", RunStatus.ACTIVE, false, false);

    RepositoryImpact.Simulated impact =
        simulated(
            f.simulate(policy("cand").main("no-high", "HIGH", 0).requireHistory("FULL").text())
                .report(),
            "acme/a");

    assertThat(impact.runsEvaluated()).isEqualTo(2);
    assertThat(impact.runsUndeterminable()).isEqualTo(1);
    assertThat(impact.runsFailingUnderCandidate()).isZero();
  }

  // ---- what cannot be simulated ----------------------------------------------------------------

  @Test
  void aRepositoryWithNoRunsAtAllIsNotSimulatedAndNeverCountsAsPassing() {
    SimFixture f =
        estate()
            .onboard("acme/new", "tier-1", "enforce", "current")
            .onboard("acme/ok", "tier-1", "enforce", "current")
            .run("acme/ok", "r1", 1);

    ImpactReport report = f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report();

    assertThat(notSimulated(report, "acme/new").reason())
        .isEqualTo(NotSimulatedReason.NO_RUNS_IN_WINDOW);
    assertThat(report.summary().overall().passingUnderCandidate()).isEqualTo(1);
    assertThat(report.summary().overall().notSimulated()).isEqualTo(1);
  }

  @Test
  void aRepositoryWhoseOnlyRunsAreOutsideTheWindowHasNoRunsInWindow() {
    SimFixture f =
        estate().onboard("acme/a", "tier-1", "enforce", "current").run("acme/a", "ancient", 300);

    assertThat(
            notSimulated(
                    f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report(), "acme/a")
                .reason())
        .isEqualTo(NotSimulatedReason.NO_RUNS_IN_WINDOW);
  }

  @Test
  void aRepositoryWithOnlyDegradedRunsIsNotSimulated() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run("acme/a", "r1", 1, "FULL", RunStatus.ACTIVE, true, false);

    assertThat(
            notSimulated(
                    f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report(), "acme/a")
                .reason())
        .isEqualTo(NotSimulatedReason.NO_COMPLETE_RUN_IN_WINDOW);
  }

  @Test
  void aRepositoryWhoseFindingsCannotBeReadIsNotSimulatedAndTheRestAre() {
    SimFixture f =
        estate()
            .onboard("acme/broken", "tier-1", "enforce", "current")
            .onboard("acme/fine", "tier-1", "enforce", "current")
            .run("acme/broken", "b1", 1, finding("h1", Severity.HIGH))
            .run("acme/fine", "f1", 1, finding("m1", Severity.MEDIUM));
    StoreBackedRetainedEvidence real =
        new StoreBackedRetainedEvidence(f.registry, f.runs, f.findings, f.suppressions);
    RetainedEvidence flaky =
        new DelegatingEvidence(real) {
          @Override
          public List<com.debthunter.controlplane.store.findings.PseudonymisedFinding> findingsOf(
              String runId) {
            if (runId.equals("b1")) {
              throw new IllegalStateException("evidence purged");
            }
            return super.findingsOf(runId);
          }
        };

    ImpactReport report =
        completed(
                f.simulatorOver(flaky)
                    .simulate(
                        policy("cand").main("no-high", "HIGH", 0).text(),
                        SimulationRequest.defaults()))
            .report();

    assertThat(notSimulated(report, "acme/broken").reason())
        .isEqualTo(NotSimulatedReason.EVIDENCE_UNAVAILABLE);
    assertThat(simulated(report, "acme/fine").candidatePasses()).isTrue();
    assertThat(report.notSimulatedShare()).isEqualTo(0.5);
    assertThat(report.isComplete()).isFalse();
  }

  @Test
  void aRegistryThatCannotBeReadFailsTheWholeSimulationRatherThanReportingAnEmptyEstate() {
    SimFixture f = estate();
    RetainedEvidence down =
        new DelegatingEvidence(
            new StoreBackedRetainedEvidence(f.registry, f.runs, f.findings, f.suppressions)) {
          @Override
          public List<com.debthunter.controlplane.store.registry.OnboardedRepository> estate() {
            throw new IllegalStateException("database down");
          }
        };

    org.assertj.core.api.Assertions.assertThatIllegalStateException()
        .isThrownBy(
            () ->
                f.simulatorOver(down)
                    .simulate(
                        policy("cand").main("no-high", "HIGH", 0).text(),
                        SimulationRequest.defaults()));
  }

  // ---- the estate summary ----------------------------------------------------------------------

  @Test
  void theSummaryCountsPassFailAndChangeForTheWholeEstate() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .onboard("acme/b", "tier-1", "enforce", "current")
            .onboard("acme/c", "tier-2", "advisory", "current")
            .onboard("acme/d", "tier-2", "advisory", "current")
            .run("acme/a", "ra", 1, finding("h1", Severity.HIGH))
            .run("acme/b", "rb", 1, finding("c1", Severity.CRITICAL))
            .run("acme/c", "rc", 1, finding("m1", Severity.MEDIUM));
    // acme/d has no runs

    GroupSummary overall =
        f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report().summary().overall();

    assertThat(overall.repositories()).isEqualTo(4);
    assertThat(overall.simulated()).isEqualTo(3);
    assertThat(overall.notSimulated()).isEqualTo(1);
    assertThat(overall.failingUnderCandidate()).isEqualTo(2);
    assertThat(overall.passingUnderCandidate()).isEqualTo(1);
    assertThat(overall.newlyFailing()).isEqualTo(1); // acme/a only; acme/b already fails
    assertThat(overall.newlyPassing()).isZero();
    assertThat(overall.notSimulatedShare()).isEqualTo(0.25);
  }

  @Test
  void theSummaryIsSlicedByTierByStageAndByBoth() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .onboard("acme/b", "tier-1", "advisory", "current")
            .onboard("acme/c", "tier-2", "advisory", "current")
            .run("acme/a", "ra", 1, finding("h1", Severity.HIGH))
            .run("acme/b", "rb", 1)
            .run("acme/c", "rc", 1, finding("h2", Severity.HIGH));

    EstateSummary summary =
        f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report().summary();

    assertThat(summary.byTier())
        .extracting(
            GroupSummary::tier, GroupSummary::repositories, GroupSummary::failingUnderCandidate)
        .containsExactly(
            org.assertj.core.api.Assertions.tuple("tier-1", 2, 1),
            org.assertj.core.api.Assertions.tuple("tier-2", 1, 1));
    assertThat(summary.byStage())
        .extracting(
            GroupSummary::stage, GroupSummary::repositories, GroupSummary::failingUnderCandidate)
        .containsExactly(
            org.assertj.core.api.Assertions.tuple("advisory", 2, 1),
            org.assertj.core.api.Assertions.tuple("enforce", 1, 1));
    assertThat(summary.byTierAndStage())
        .extracting(GroupSummary::tier, GroupSummary::stage, GroupSummary::repositories)
        .containsExactly(
            org.assertj.core.api.Assertions.tuple("tier-1", "advisory", 1),
            org.assertj.core.api.Assertions.tuple("tier-1", "enforce", 1),
            org.assertj.core.api.Assertions.tuple("tier-2", "advisory", 1));
  }

  @Test
  void everyRepositoryIsInExactlyOneSliceOfEachGrouping() {
    SimFixture f = estate();
    for (int i = 0; i < 9; i++) {
      f.onboard("acme/r" + i, "tier-" + (i % 3), i % 2 == 0 ? "enforce" : "advisory", "current");
      if (i % 4 != 0) {
        f.run("acme/r" + i, "run" + i, 1, finding("h" + i, Severity.HIGH));
      }
    }

    EstateSummary summary =
        f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report().summary();

    for (List<GroupSummary> grouping :
        List.of(summary.byTier(), summary.byStage(), summary.byTierAndStage())) {
      assertThat(grouping.stream().mapToInt(GroupSummary::repositories).sum()).isEqualTo(9);
      assertThat(grouping.stream().mapToInt(GroupSummary::simulated).sum())
          .isEqualTo(summary.overall().simulated());
      assertThat(grouping.stream().mapToInt(GroupSummary::notSimulated).sum())
          .isEqualTo(summary.overall().notSimulated());
    }
  }

  @Test
  void aSliceWithNothingSimulatedStatesItsWholeSliceIsUnsimulated() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .onboard("acme/b", "tier-9", "enforce", "current")
            .run("acme/a", "ra", 1);

    EstateSummary summary =
        f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report().summary();

    GroupSummary tier9 =
        summary.byTier().stream().filter(g -> "tier-9".equals(g.tier())).findFirst().orElseThrow();
    assertThat(tier9.simulated()).isZero();
    assertThat(tier9.notSimulatedShare()).isEqualTo(1.0);
    assertThat(tier9.passingUnderCandidate()).as("not a clean tier, an unknown one").isZero();
  }

  @Test
  void anEmptyEstateProducesAnEmptyCompleteReport() {
    ImpactReport report =
        estate().simulate(policy("cand").main("no-high", "HIGH", 0).text()).report();

    assertThat(report.repositories()).isEmpty();
    assertThat(report.summary().overall().repositories()).isZero();
    assertThat(report.notSimulatedShare()).isZero();
  }

  @Test
  void repositoriesAreReportedInIdentifierOrder() {
    SimFixture f =
        estate()
            .onboard("zeta/z", "tier-1", "enforce", "current")
            .onboard("acme/a", "tier-1", "enforce", "current")
            .onboard("mid/m", "tier-1", "enforce", "current");

    assertThat(f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report().repositories())
        .extracting(RepositoryImpact::repository)
        .containsExactly("acme/a", "mid/m", "zeta/z");
  }

  @Test
  void newlyBlockingFindingsAreOrderedWorstFirst() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run(
                "acme/a",
                "r1",
                1,
                finding("h2", Severity.HIGH, Category.HOTSPOT, "b.java", true),
                finding("m1", Severity.MEDIUM, Category.HOTSPOT, "a.java", true),
                finding("h1", Severity.HIGH, Category.HOTSPOT, "a.java", true));

    RepositoryImpact.Simulated impact =
        simulated(
            f.simulate(policy("cand").main("no-medium", "MEDIUM", 0).text()).report(), "acme/a");

    assertThat(impact.newlyBlocking())
        .extracting(BlockingFinding::findingId)
        .containsExactly("h1", "h2", "m1");
  }

  // ---- rejection and request validation --------------------------------------------------------

  @Test
  void aCandidateWithErrorsIsRejectedAndReportsWhy() {
    SimFixture f =
        estate().onboard("acme/a", "tier-1", "enforce", "current").run("acme/a", "r1", 1);

    SimulationOutcome outcome =
        f.simulator().simulate("version: [broken", SimulationRequest.defaults());

    assertThat(outcome).isInstanceOf(SimulationOutcome.Rejected.class);
    assertThat(((SimulationOutcome.Rejected) outcome).validation().errors())
        .extracting(PolicyIssue::code)
        .containsExactly("SCHEMA");
  }

  @Test
  void warningsDoNotStopASimulationAndAreCarriedInTheReport() {
    SimFixture f =
        estate()
            .onboard("acme/a", "tier-1", "enforce", "current")
            .run("acme/a", "r1", 1, finding("h1", Severity.HIGH));

    ImpactReport report =
        f.simulate(policy("cand").main("no-high", "HIGH", 0).main("few-high", "HIGH", 4).text())
            .report();

    assertThat(report.validation().warnings())
        .extracting(PolicyIssue::code)
        .contains("SHADOWED_RULE");
  }

  @Test
  void anImpossibleThresholdIsWarnedAboutAndStillSimulated() {
    SimFixture f = estate();
    for (int i = 0; i < 4; i++) {
      f.onboard("acme/r" + i, "tier-1", "enforce", "current")
          .run("acme/r" + i, "run" + i, 1, finding("h" + i, Severity.HIGH));
    }

    ImpactReport report = f.simulate(policy("cand").main("no-high", "HIGH", 0).text()).report();

    assertThat(report.validation().warnings())
        .extracting(PolicyIssue::code)
        .containsExactly("IMPOSSIBLE_THRESHOLD");
    assertThat(report.summary().overall().failingUnderCandidate()).isEqualTo(4);
  }

  @Test
  void validateAloneChecksTheThresholdsAgainstTheEstateWithoutSimulating() {
    SimFixture f = estate();
    for (int i = 0; i < 3; i++) {
      f.onboard("acme/r" + i, "tier-1", "enforce", "current")
          .run("acme/r" + i, "run" + i, 1, finding("h" + i, Severity.HIGH));
    }

    var validation =
        f.simulator()
            .validate(
                policy("cand").main("no-high", "HIGH", 0).text(), SimulationRequest.defaults());

    assertThat(validation.isValid()).isTrue();
    assertThat(validation.warnings())
        .extracting(PolicyIssue::code)
        .contains("IMPOSSIBLE_THRESHOLD");
  }

  @Test
  void theRequestRejectsNonsenseWindows() {
    assertThatIllegalArgumentException().isThrownBy(() -> new SimulationRequest(Duration.ZERO, 10));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SimulationRequest(Duration.ofDays(-1), 10));
    assertThatIllegalArgumentException().isThrownBy(() -> new SimulationRequest(null, 10));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SimulationRequest(Duration.ofDays(731), 10));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SimulationRequest(Duration.ofDays(90), 0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> new SimulationRequest(Duration.ofDays(90), 501));
  }

  @Test
  void theLongestAndShortestAcceptedWindowsAreAccepted() {
    assertThat(new SimulationRequest(Duration.ofDays(730), 500)).isNotNull();
    assertThat(new SimulationRequest(Duration.ofSeconds(1), 1)).isNotNull();
  }

  @Test
  void aNullRequestIsRefused() {
    assertThatNullPointerException()
        .isThrownBy(() -> estate().simulator().simulate("version: \"1\"", null));
    assertThatNullPointerException()
        .isThrownBy(() -> estate().simulator().validate("version: \"1\"", null));
  }

  @Test
  void theReportRecordsWhenAndOverWhatItWasRun() {
    ImpactReport report =
        estate().simulate(policy("cand").main("no-high", "HIGH", 0).text()).report();

    assertThat(report.asOf()).isEqualTo(SimFixture.NOW);
    assertThat(report.lookback()).isEqualTo(Duration.ofDays(90));
  }
}
