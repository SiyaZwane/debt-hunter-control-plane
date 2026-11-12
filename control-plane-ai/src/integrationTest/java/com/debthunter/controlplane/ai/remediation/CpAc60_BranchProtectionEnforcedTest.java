package com.debthunter.controlplane.ai.remediation;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ai.AiFixtures;
import com.debthunter.controlplane.ai.remediation.host.FakeGitHubServer;
import com.debthunter.controlplane.store.queue.QueueEntry;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-60: branch protection is respected. If the host protects the branch the proposal would use,
 * nothing is written to the host at all; protecting the base branch does not get in the way because
 * a pull request is the proper way in.
 */
@Tag("integration")
class CpAc60_BranchProtectionEnforcedTest {

  private static final String BRANCH =
      BranchNames.branchFor(RemediationHarness.REPO, "fp-hotspot-foo");

  @Test
  void cpAc60_aRulesetOnTheProposalBranchStopsEverythingBeforeAnyWrite() throws Exception {
    try (RemediationHarness h = new RemediationHarness()) {
      h.github.rulesetCovers = name -> name.startsWith("debt-hunter/");
      h.storeRun("run-1", AiFixtures.hotspot());
      h.requests.request(
          RemediationHarness.REPO, "run-1", AiFixtures.hotspot().id(), "main", "alice");

      h.worker.runOnce();

      assertThat(h.github.writes()).as("not a single write").isEmpty();
      assertThat(h.github.pullRequests).isEmpty();
      assertThat(h.github.branches).containsOnlyKeys("main");
      QueueEntry entry = h.queue.find(1).orElseThrow();
      assertThat(entry.status()).isEqualTo(QueueEntry.Status.DEAD);
      assertThat(entry.lastError()).contains("PROTECTED_BRANCH");
    }
  }

  @Test
  void cpAc60_aProtectionRuleOnAnExistingProposalBranchAlsoStopsIt() throws Exception {
    try (RemediationHarness h = new RemediationHarness()) {
      h.github.withBranch(BRANCH, "sha-main");
      h.github.protectedBranches.add(BRANCH);
      h.storeRun("run-1", AiFixtures.hotspot());
      h.requests.request(
          RemediationHarness.REPO, "run-1", AiFixtures.hotspot().id(), "main", "alice");

      h.worker.runOnce();

      assertThat(h.github.writes()).isEmpty();
      assertThat(h.github.files).isEmpty();
    }
  }

  @Test
  void cpAc60_aProtectedBaseBranchIsStillUnchangedAndTheProposalIsOpenedAsAPullRequest()
      throws Exception {
    try (RemediationHarness h = new RemediationHarness()) {
      h.github.protectedBranches.add("main");
      h.storeRun("run-1", AiFixtures.hotspot());
      h.requests.request(
          RemediationHarness.REPO, "run-1", AiFixtures.hotspot().id(), "main", "alice");

      h.worker.runOnce();

      assertThat(h.github.pullRequests).hasSize(1);
      assertThat(h.github.branches.get("main")).isEqualTo("sha-main");
      assertThat(h.github.writes())
          .extracting(FakeGitHubServer.Request::path)
          .noneMatch(p -> p.contains("/heads/main"));
    }
  }

  @Test
  void cpAc60_ifTheHostRefusesTheWriteAnywayNothingLandsAndTheRequestIsEventuallySetAside()
      throws Exception {
    try (RemediationHarness h = new RemediationHarness()) {
      // The check says the branch is free, but the host refuses writes to it (a protection that
      // appeared in between). The write is refused, and nothing is merged or forced through.
      h.github.withBranch(BRANCH, "sha-main");
      h.github.rulesetCovers = name -> false;
      h.github.protectedBranches.add(BRANCH);
      h.storeRun("run-1", AiFixtures.hotspot());
      h.requests.request(
          RemediationHarness.REPO, "run-1", AiFixtures.hotspot().id(), "main", "alice");

      for (int i = 0; i < 5; i++) {
        h.worker.runOnce();
        h.clock.advance(java.time.Duration.ofMinutes(1));
      }

      assertThat(h.github.files).isEmpty();
      assertThat(h.github.pullRequests).isEmpty();
      assertThat(h.github.requests)
          .noneMatch(r -> r.path().contains("/merge") || r.method().equals("DELETE"));
    }
  }
}
