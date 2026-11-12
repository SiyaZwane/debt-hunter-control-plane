package com.debthunter.controlplane.ai.remediation.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Against a local imitation written from Azure DevOps' documentation. This shows the adapter agrees
 * with that imitation; it has not been run against Azure DevOps.
 */
class AzureDevOpsSourceHostClientTest {

  private static final String REPO = "Platform/widgets";
  private static final String PAT = "pat-supersecret-0123456789";

  private final FakeAzureDevOpsServer server =
      new FakeAzureDevOpsServer().withBranch("main", "sha-main");
  private final AzureDevOpsSourceHostClient client =
      new AzureDevOpsSourceHostClient(server.uri(), "acme", () -> PAT, Duration.ofSeconds(5));

  @AfterEach
  void stop() {
    server.close();
  }

  @Test
  void aBranchsHeadIsItsCommitAndAMissingBranchIsEmpty() {
    assertThat(client.branchHead(REPO, "main")).contains("sha-main");
    assertThat(client.branchHead(REPO, "nope")).isEmpty();
  }

  @Test
  void aBranchCoveredByAnEnabledPolicyIsProtected() {
    server.policyProtected.add("main");

    assertThat(client.isProtected(REPO, "main")).isTrue();
    assertThat(client.isProtected(REPO, "debt-hunter/fix-x")).isFalse();
  }

  @Test
  void aBranchWithOneFileIsCreatedFromTheBaseCommitAndRepeatingItWritesNothingMore() {
    client.ensureBranchWithFile(
        REPO, "debt-hunter/fix-x", "sha-main", ".debt-hunter/proposals/x.md", "# hi", "msg");
    int writes = (int) server.requests.stream().filter(r -> r.method().equals("POST")).count();

    client.ensureBranchWithFile(
        REPO, "debt-hunter/fix-x", "sha-main", ".debt-hunter/proposals/x.md", "# hi", "msg");

    assertThat(server.refs).containsKey("refs/heads/debt-hunter/fix-x");
    assertThat(server.files)
        .containsEntry("debt-hunter/fix-x:/.debt-hunter/proposals/x.md", "# hi");
    assertThat(server.requests.stream().filter(r -> r.method().equals("POST")).count())
        .isEqualTo(writes);
  }

  @Test
  void aChangedFileIsPushedAsAnEdit() {
    client.ensureBranchWithFile(REPO, "debt-hunter/fix-x", "sha-main", "p.md", "v1", "m");

    client.ensureBranchWithFile(REPO, "debt-hunter/fix-x", "sha-main", "p.md", "v2", "m");

    assertThat(server.files).containsEntry("debt-hunter/fix-x:/p.md", "v2");
    assertThat(
            server.requests.stream()
                .filter(r -> r.path().endsWith("/pushes"))
                .reduce((a, b) -> b)
                .orElseThrow()
                .body())
        .contains("\"changeType\":\"edit\"");
  }

  @Test
  void theDraftPullRequestIsOpenedLabelledAndItsAddressBuilt() {
    client.ensureBranchWithFile(REPO, "debt-hunter/fix-x", "sha-main", "p.md", "v", "m");

    PullRequestRef pr =
        client.openDraftPullRequest(
            REPO, "debt-hunter/fix-x", "main", "T", "B", List.of("debt-hunter", "auto-generated"));

    FakeAzureDevOpsServer.Pr created = server.pullRequests.get(0);
    assertThat(created.draft()).isTrue();
    assertThat(created.source()).isEqualTo("debt-hunter/fix-x");
    assertThat(created.target()).isEqualTo("main");
    assertThat(created.labels()).containsExactly("debt-hunter", "auto-generated");
    assertThat(pr.url()).endsWith("/acme/Platform/_git/widgets/pullrequest/100");
    assertThat(client.findOpenPullRequest(REPO, "debt-hunter/fix-x"))
        .hasValueSatisfying(p -> assertThat(p.number()).isEqualTo(100));
    assertThat(client.findOpenPullRequest(REPO, "other")).isEmpty();
  }

  @Test
  void theTokenIsSentAsBasicCredentialsAndNeverAppearsInAnError() {
    client.branchHead(REPO, "main");
    String expected =
        "Basic " + Base64.getEncoder().encodeToString((":" + PAT).getBytes(StandardCharsets.UTF_8));
    assertThat(server.requests.get(0).authorization()).isEqualTo(expected);

    server.forcedStatus = 503;
    Throwable t =
        org.assertj.core.api.Assertions.catchThrowable(() -> client.branchHead(REPO, "main"));
    assertThat(t).isInstanceOf(SourceHostUnavailableException.class);
    assertThat(t.toString()).doesNotContain(PAT);
  }

  @Test
  void aRefusalIsARejectionAndAnOutageIsUnavailable() {
    server.forcedStatus = 403;
    assertThatThrownBy(() -> client.branchHead(REPO, "main"))
        .isInstanceOf(SourceHostRejectedException.class);
    server.close();
    assertThatThrownBy(() -> client.branchHead(REPO, "main"))
        .isInstanceOf(SourceHostUnavailableException.class);
  }

  @Test
  void aWholeProposalOnlyEverCreatesAndNeverMergesDeletesOrUpdates() {
    client.branchHead(REPO, "main");
    client.isProtected(REPO, "debt-hunter/fix-x");
    client.ensureBranchWithFile(REPO, "debt-hunter/fix-x", "sha-main", "p.md", "v", "m");
    client.openDraftPullRequest(REPO, "debt-hunter/fix-x", "main", "T", "B", List.of());

    assertThat(server.requests).allSatisfy(r -> assertThat(r.method()).isIn("GET", "POST"));
    assertThat(server.refs.get("refs/heads/main")).isEqualTo("sha-main");
  }

  @Test
  void anOrganisationIsRequiredAndABadRepositoryNameIsRefused() {
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new AzureDevOpsSourceHostClient(
                    server.uri(), " ", () -> PAT, Duration.ofSeconds(1)));
    assertThatThrownBy(() -> client.branchHead("noslash", "main"))
        .isInstanceOf(SourceHostRejectedException.class);
  }
}
