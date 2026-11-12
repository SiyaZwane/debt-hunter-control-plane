package com.debthunter.controlplane.ai.remediation;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ai.AiFixtures;
import com.debthunter.controlplane.ai.remediation.host.FakeGitHubServer;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.queue.QueueEntry;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import java.util.Map;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-59: from a request to a draft pull request, over a real database and a GitHub stand-in. The
 * pull request comes from a new branch the agent made, never from or into a changed default branch.
 */
@Tag("integration")
class CpAc59_PrOnNonProtectedBranchTest {

  private static final PseudonymisedFinding FINDING =
      AiFixtures.finding(
          "codemaat.hotspot:src/Foo.java",
          "codemaat.hotspot",
          Category.HOTSPOT,
          Severity.HIGH,
          "src/Foo.java",
          "src/Foo.java is a change hotspot, ping @everyone about #42",
          Map.of("changeFrequency", 42.0),
          "fp-foo");

  @Test
  void cpAc59_aRequestEndsAsADraftPullRequestFromANewBranchWithOneProposalFile() throws Exception {
    try (RemediationHarness h = new RemediationHarness()) {
      h.storeRun("run-1", FINDING);

      RequestOutcome outcome =
          h.requests.request(RemediationHarness.REPO, "run-1", FINDING.id(), "main", "alice");
      h.worker.runOnce();

      assertThat(outcome).isInstanceOf(RequestOutcome.Queued.class);
      String branch = BranchNames.branchFor(RemediationHarness.REPO, "fp-foo");
      FakeGitHubServer.Pr pr = h.github.pullRequests.get(0);
      assertThat(pr.draft()).isTrue();
      assertThat(pr.head()).isEqualTo(branch).startsWith("debt-hunter/fix-");
      assertThat(pr.base()).isEqualTo("main");
      assertThat(pr.labels()).containsExactly("debt-hunter", "auto-generated");
      assertThat(h.github.files)
          .containsOnlyKeys(branch + ":" + BranchNames.fileFor(RemediationHarness.REPO, "fp-foo"));
    }
  }

  @Test
  void cpAc59_theBaseBranchIsNeverWrittenTo() throws Exception {
    try (RemediationHarness h = new RemediationHarness()) {
      h.storeRun("run-1", FINDING);
      h.requests.request(RemediationHarness.REPO, "run-1", FINDING.id(), "main", "alice");

      h.worker.runOnce();

      assertThat(h.github.branches.get("main")).isEqualTo("sha-main");
      assertThat(h.github.files.keySet()).noneMatch(k -> k.startsWith("main:"));
      assertThat(h.github.writes())
          .extracting(FakeGitHubServer.Request::method)
          .containsOnly("POST", "PUT");
      assertThat(h.github.requests)
          .noneMatch(
              r ->
                  r.path().contains("/merge")
                      || r.method().equals("DELETE")
                      || r.method().equals("PATCH"));
    }
  }

  @Test
  void cpAc59_theDescriptionHasTheFindingTheEvidenceWhoWroteItAndALinkToTheRun() throws Exception {
    try (RemediationHarness h = new RemediationHarness()) {
      h.storeRun("run-1", FINDING);
      h.requests.request(RemediationHarness.REPO, "run-1", FINDING.id(), "main", "alice");

      h.worker.runOnce();

      String body = h.github.pullRequests.get(0).body();
      assertThat(body)
          .contains("`codemaat.hotspot:src/Foo.java`")
          .contains("`changeFrequency`: `42.0`")
          .contains("No AI model was used")
          .contains("[Open the run in Debt Hunter](https://cp.example/runs/run-1)");
      assertThat(body.replaceAll("`[^`]*`", "")).doesNotContain("@everyone").doesNotContain("#42");
      assertThat(body).doesNotContain(AiFixtures.CONTRIBUTOR);
    }
  }

  @Test
  void cpAc59_theQueuedRequestIsFinishedAndHoldsNothingAfterwards() throws Exception {
    try (RemediationHarness h = new RemediationHarness()) {
      h.storeRun("run-1", FINDING);
      h.requests.request(RemediationHarness.REPO, "run-1", FINDING.id(), "main", "alice");

      h.worker.runOnce();

      QueueEntry entry = h.queue.find(1).orElseThrow();
      assertThat(entry.status()).isEqualTo(QueueEntry.Status.DONE);
      assertThat(entry.payloadBytes()).isZero();
    }
  }

  @Test
  void cpAc59_askingAgainForTheSameFindingAddsNoSecondPullRequest() throws Exception {
    try (RemediationHarness h = new RemediationHarness()) {
      h.storeRun("run-1", FINDING);
      h.requests.request(RemediationHarness.REPO, "run-1", FINDING.id(), "main", "alice");
      h.worker.runOnce();

      h.requests.request(RemediationHarness.REPO, "run-1", FINDING.id(), "main", "bob");
      h.worker.runOnce();

      assertThat(h.github.pullRequests).hasSize(1);
    }
  }

  @Test
  void cpAc59_aRepositoryThatNeverOptedInGetsNothing() throws Exception {
    try (RemediationHarness h = new RemediationHarness()) {
      h.storeRun("run-1", FINDING);
      h.enabled.clear();

      RequestOutcome outcome =
          h.requests.request(RemediationHarness.REPO, "run-1", FINDING.id(), "main", "alice");

      assertThat(outcome).isInstanceOf(RequestOutcome.Refused.class);
      h.worker.runOnce();
      assertThat(h.github.requests).isEmpty();
    }
  }
}
