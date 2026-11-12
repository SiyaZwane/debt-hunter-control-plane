package com.debthunter.controlplane.ai.remediation;

import static com.debthunter.controlplane.ai.AiFixtures.CLOCK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.debthunter.controlplane.ai.AiFixtures;
import com.debthunter.controlplane.ai.remediation.host.FakeSourceHost;
import com.debthunter.controlplane.ai.remediation.host.SourceHostClient;
import com.debthunter.controlplane.ai.remediation.host.SourceHostRejectedException;
import com.debthunter.controlplane.ai.remediation.host.SourceHostUnavailableException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/** CP-AC-59 and CP-AC-60: a draft PR on a branch of its own, and never on a protected one. */
class FixAgentPrCreatorTest {

  private static final String REPO = "acme/widgets";

  private final FakeSourceHost host = new FakeSourceHost().withBranch("main", "sha-main");
  private final FixAgentPrCreator creator =
      new FixAgentPrCreator(host, runId -> Optional.of("https://cp.example/runs/" + runId));
  private final RemediationRequest request =
      new RemediationRequest(REPO, "run-1", AiFixtures.hotspot(), "main");
  private final RemediationProposal proposal =
      new RuleBasedRemediationAdvisor(CLOCK).propose(request).orElseThrow();

  // ---- CP-AC-59 ------------------------------------------------------------------------------

  @Test
  void aDraftPullRequestIsOpenedFromANewBranchOfItsOwnIntoTheBaseBranch() {
    PrOutcome outcome = creator.create(request, proposal);

    assertThat(outcome).isInstanceOf(PrOutcome.Opened.class);
    FakeSourceHost.Pr pr = host.pullRequests.get(0);
    assertThat(pr.head())
        .startsWith(BranchNames.PREFIX)
        .isEqualTo(BranchNames.branchFor(REPO, request.finding().fingerprint()));
    assertThat(pr.base()).isEqualTo("main");
    assertThat(pr.labels()).containsExactly("debt-hunter", "auto-generated");
    assertThat(pr.body())
        .contains(request.finding().id())
        .contains("No AI model was used")
        .contains("https://cp.example/runs/run-1");
  }

  @Test
  void theOnlyFileOnTheBranchIsTheProposalDocumentAndTheBaseBranchIsNeverTouched() {
    creator.create(request, proposal);

    String head = BranchNames.branchFor(REPO, request.finding().fingerprint());
    assertThat(host.files)
        .containsOnlyKeys(head + ":" + BranchNames.fileFor(REPO, request.finding().fingerprint()));
    assertThat(host.files.keySet()).allMatch(k -> k.startsWith(head + ":" + BranchNames.DIRECTORY));
    assertThat(host.branches.get("main")).isEqualTo("sha-main");
    assertThat(host.calls)
        .noneMatch(c -> c.contains(" main") && c.startsWith("ensureBranchWithFile"));
  }

  @Test
  void theBranchIsBranchedFromTheBaseBranchesCurrentCommit() {
    creator.create(request, proposal);

    assertThat(host.branches.get(BranchNames.branchFor(REPO, request.finding().fingerprint())))
        .isEqualTo("sha-main");
  }

  @Test
  void aFindingThatAlreadyHasAnOpenPullRequestGetsNoSecondOne() {
    creator.create(request, proposal);
    int writes =
        (int)
            host.calls.stream().filter(c -> c.startsWith("ensure") || c.startsWith("open")).count();

    PrOutcome again = creator.create(request, proposal);

    assertThat(again)
        .isInstanceOfSatisfying(PrOutcome.Opened.class, o -> assertThat(o.alreadyOpen()).isTrue());
    assertThat(host.pullRequests).hasSize(1);
    assertThat(
            host.calls.stream().filter(c -> c.startsWith("ensure") || c.startsWith("open")).count())
        .isEqualTo(writes);
  }

  @Test
  void theBranchNameIsStableForAFindingAndDiffersAcrossFindingsAndRepositories() {
    String a = BranchNames.branchFor(REPO, "fp-1");

    assertThat(BranchNames.branchFor(REPO, "fp-1")).isEqualTo(a);
    assertThat(BranchNames.branchFor(REPO, "fp-2")).isNotEqualTo(a);
    assertThat(BranchNames.branchFor("acme/other", "fp-1")).isNotEqualTo(a);
    assertThat(a).matches("debt-hunter/fix-[0-9a-f]{12}");
  }

  @Test
  void aMissingBaseBranchIsRefusedAndNothingIsWritten() {
    RemediationRequest toNowhere =
        new RemediationRequest(REPO, "run-1", AiFixtures.hotspot(), "release");

    PrOutcome outcome = creator.create(toNowhere, proposal);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            PrOutcome.Refused.class,
            r -> assertThat(r.reason()).isEqualTo(PrOutcome.Reason.BASE_BRANCH_MISSING));
    assertThat(host.wasWrittenTo()).isFalse();
  }

  // ---- CP-AC-60 ------------------------------------------------------------------------------

  @Test
  void aProtectedProposalBranchIsRefusedAndNothingIsWritten() {
    host.protectedBranches.add(BranchNames.branchFor(REPO, request.finding().fingerprint()));

    PrOutcome outcome = creator.create(request, proposal);

    assertThat(outcome)
        .isInstanceOfSatisfying(
            PrOutcome.Refused.class,
            r -> assertThat(r.reason()).isEqualTo(PrOutcome.Reason.PROTECTED_BRANCH));
    assertThat(host.wasWrittenTo()).isFalse();
    assertThat(host.pullRequests).isEmpty();
  }

  @Test
  void aProtectedBaseBranchIsNotAProblemBecauseAPullRequestIsTheProperWayIn() {
    host.protectedBranches.add("main");

    assertThat(creator.create(request, proposal)).isInstanceOf(PrOutcome.Opened.class);
    assertThat(host.branches.get("main")).as("main itself was never changed").isEqualTo("sha-main");
  }

  @Test
  void theHostClientHasNoWayToMergeForcePushDeleteOrChangeProtection() {
    for (Method m : SourceHostClient.class.getMethods()) {
      assertThat(m.getName().toLowerCase())
          .doesNotContain("merge")
          .doesNotContain("delete")
          .doesNotContain("remove")
          .doesNotContain("force")
          .doesNotContain("approve")
          .doesNotContain("update")
          .doesNotContain("protect" + "ion");
    }
    assertThat(Arrays.stream(SourceHostClient.class.getMethods()).map(Method::getName))
        .containsExactlyInAnyOrder(
            "branchHead",
            "isProtected",
            "ensureBranchWithFile",
            "findOpenPullRequest",
            "openDraftPullRequest");
  }

  // ---- host failures -------------------------------------------------------------------------

  @Test
  void anUnavailableHostIsReportedAsSuchAndLeavesNoPullRequest() {
    host.failWith = new SourceHostUnavailableException("down", null);

    assertThatThrownBy(() -> creator.create(request, proposal))
        .isInstanceOf(SourceHostUnavailableException.class);
    assertThat(host.pullRequests).isEmpty();
  }

  @Test
  void aHostThatRefusesIsReportedAsSuch() {
    host.failWith = new SourceHostRejectedException("no permission");

    assertThatThrownBy(() -> creator.create(request, proposal))
        .isInstanceOf(SourceHostRejectedException.class);
  }

  @Test
  void aRetryAfterABranchWasCreatedButNoPullRequestIsFine() {
    host.branches.put(BranchNames.branchFor(REPO, request.finding().fingerprint()), "sha-main");

    PrOutcome outcome = creator.create(request, proposal);

    assertThat(outcome).isInstanceOf(PrOutcome.Opened.class);
    assertThat(host.pullRequests).hasSize(1);
  }
}
