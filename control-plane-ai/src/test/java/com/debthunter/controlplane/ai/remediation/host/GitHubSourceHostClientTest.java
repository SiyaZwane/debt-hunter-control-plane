package com.debthunter.controlplane.ai.remediation.host;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GitHubSourceHostClientTest {

  private static final String REPO = "acme/widgets";
  private static final String TOKEN = "ghp_supersecrettoken1234567890";

  private final FakeGitHubServer server = new FakeGitHubServer().withBranch("main", "sha-main");
  private final AtomicReference<String> token = new AtomicReference<>(TOKEN);
  private final GitHubSourceHostClient client =
      new GitHubSourceHostClient(server.uri(), token::get, Duration.ofSeconds(5));

  @AfterEach
  void stop() {
    server.close();
  }

  // ---- reading ------------------------------------------------------------------------------

  @Test
  void aBranchsHeadIsItsCommitAndAMissingBranchIsEmpty() {
    server.withBranch("debt-hunter/fix-abc", "sha-x");

    assertThat(client.branchHead(REPO, "main")).contains("sha-main");
    assertThat(client.branchHead(REPO, "debt-hunter/fix-abc")).contains("sha-x");
    assertThat(client.branchHead(REPO, "nope")).isEmpty();
  }

  @Test
  void protectionIsReadFromTheBranchAndFromRulesetsEvenForABranchThatDoesNotExistYet() {
    server.withBranch("guarded", "sha-g");
    server.protectedBranches.add("guarded");
    server.rulesetCovers = name -> name.startsWith("debt-hunter/");

    assertThat(client.isProtected(REPO, "guarded")).as("protection rule").isTrue();
    assertThat(client.isProtected(REPO, "debt-hunter/fix-new"))
        .as("ruleset on an unborn name")
        .isTrue();
    assertThat(client.isProtected(REPO, "main")).as("neither").isFalse();
  }

  @Test
  void anUnprotectedBranchWithNoRulesetIsNotProtected() {
    assertThat(client.isProtected(REPO, "debt-hunter/fix-new")).isFalse();
  }

  @Test
  void anOpenPullRequestFromABranchIsFoundAndNoneIsEmpty() {
    assertThat(client.findOpenPullRequest(REPO, "debt-hunter/fix-x")).isEmpty();
    client.ensureBranchWithFile(REPO, "debt-hunter/fix-x", "sha-main", "p.md", "hello", "msg");
    client.openDraftPullRequest(REPO, "debt-hunter/fix-x", "main", "t", "b", List.of());

    Optional<PullRequestRef> found = client.findOpenPullRequest(REPO, "debt-hunter/fix-x");

    assertThat(found).isPresent();
    assertThat(found.get().number()).isEqualTo(1);
  }

  // ---- writing ------------------------------------------------------------------------------

  @Test
  void aBranchWithOneFileIsCreatedFromTheBaseCommit() {
    client.ensureBranchWithFile(
        REPO, "debt-hunter/fix-x", "sha-main", ".debt-hunter/proposals/x.md", "# hi", "msg");

    assertThat(server.branches).containsEntry("debt-hunter/fix-x", "sha-main");
    assertThat(server.files).containsEntry("debt-hunter/fix-x:.debt-hunter/proposals/x.md", "# hi");
  }

  @Test
  void repeatingItIsHarmlessAndWritesNothingTheSecondTime() {
    client.ensureBranchWithFile(REPO, "debt-hunter/fix-x", "sha-main", "p.md", "same", "msg");
    int writes = server.writes().size();

    client.ensureBranchWithFile(REPO, "debt-hunter/fix-x", "sha-main", "p.md", "same", "msg");

    assertThat(server.writes().size())
        .as("only the ref attempt, which was refused as existing")
        .isEqualTo(writes + 1);
    assertThat(server.files).hasSize(1);
  }

  @Test
  void aChangedFileOnOurOwnBranchIsReplacedUsingItsCurrentBlob() {
    client.ensureBranchWithFile(REPO, "debt-hunter/fix-x", "sha-main", "p.md", "v1", "msg");

    client.ensureBranchWithFile(REPO, "debt-hunter/fix-x", "sha-main", "p.md", "v2", "msg");

    assertThat(server.files).containsEntry("debt-hunter/fix-x:p.md", "v2");
  }

  @Test
  void writingToAProtectedBranchIsRefusedByTheHost() {
    server.withBranch("debt-hunter/fix-x", "sha-main");
    server.protectedBranches.add("debt-hunter/fix-x");

    assertThatThrownBy(
            () ->
                client.ensureBranchWithFile(
                    REPO, "debt-hunter/fix-x", "sha-main", "p.md", "v", "m"))
        .isInstanceOf(SourceHostRejectedException.class);
  }

  @Test
  void theDraftPullRequestIsOpenedAsADraftAndLabelled() {
    client.ensureBranchWithFile(REPO, "debt-hunter/fix-x", "sha-main", "p.md", "v", "m");

    PullRequestRef pr =
        client.openDraftPullRequest(
            REPO,
            "debt-hunter/fix-x",
            "main",
            "A title",
            "A body",
            List.of("debt-hunter", "auto-generated"));

    FakeGitHubServer.Pr created = server.pullRequests.get(0);
    assertThat(created.draft()).isTrue();
    assertThat(created.head()).isEqualTo("debt-hunter/fix-x");
    assertThat(created.base()).isEqualTo("main");
    assertThat(created.title()).isEqualTo("A title");
    assertThat(created.labels()).containsExactly("debt-hunter", "auto-generated");
    assertThat(pr.url()).contains("/pull/1");
  }

  // ---- what it is capable of ----------------------------------------------------------------

  @Test
  void aWholeProposalOnlyEverUsesReadsAndTheThreeCreatingWrites() {
    String branch = "debt-hunter/fix-x";
    client.findOpenPullRequest(REPO, branch);
    client.branchHead(REPO, "main");
    client.isProtected(REPO, branch);
    client.ensureBranchWithFile(REPO, branch, "sha-main", "p.md", "v", "m");
    client.openDraftPullRequest(REPO, branch, "main", "t", "b", List.of("l"));

    assertThat(server.requests).allSatisfy(r -> assertThat(r.method()).isIn("GET", "POST", "PUT"));
    assertThat(server.requests).noneMatch(r -> r.path().contains("merge"));
    assertThat(server.writes())
        .extracting(FakeGitHubServer.Request::path)
        .allMatch(
            p ->
                p.endsWith("/git/refs")
                    || p.contains("/contents/")
                    || p.endsWith("/pulls")
                    || p.endsWith("/labels"));
    assertThat(server.branches.get("main")).isEqualTo("sha-main");
  }

  // ---- credentials --------------------------------------------------------------------------

  @Test
  void theTokenGoesOnlyInTheAuthorizationHeaderAndIsReadAnewForEveryRequest() {
    client.branchHead(REPO, "main");
    token.set("rotated-token-value");
    client.branchHead(REPO, "main");

    assertThat(server.requests)
        .extracting(FakeGitHubServer.Request::authorization)
        .containsExactly("Bearer " + TOKEN, "Bearer rotated-token-value");
    assertThat(server.requests)
        .noneMatch(
            r -> r.path().contains(TOKEN) || r.query().contains(TOKEN) || r.body().contains(TOKEN));
  }

  @Test
  void noErrorEverContainsTheToken() {
    server.forcedStatus = 500;
    Throwable down =
        org.assertj.core.api.Assertions.catchThrowable(() -> client.branchHead(REPO, "main"));
    server.forcedStatus = 403;
    Throwable refused =
        org.assertj.core.api.Assertions.catchThrowable(() -> client.branchHead(REPO, "main"));
    server.close();
    Throwable unreachable =
        org.assertj.core.api.Assertions.catchThrowable(() -> client.branchHead(REPO, "main"));

    for (Throwable t : new Throwable[] {down, refused, unreachable}) {
      assertThat(t).isNotNull();
      assertThat(String.valueOf(t.getMessage())).doesNotContain(TOKEN);
      assertThat(t.toString()).doesNotContain(TOKEN);
    }
  }

  // ---- failures -----------------------------------------------------------------------------

  @Test
  void serverErrorsAndRateLimitsAreUnavailableNotRefusals() {
    for (int status : new int[] {500, 502, 503, 429}) {
      server.forcedStatus = status;
      assertThatThrownBy(() -> client.branchHead(REPO, "main"))
          .as("HTTP " + status)
          .isInstanceOf(SourceHostUnavailableException.class);
    }
    server.forcedStatus = 403;
    server.forcedHeaderName = "X-RateLimit-Remaining";
    server.forcedHeaderValue = "0";
    assertThatThrownBy(() -> client.branchHead(REPO, "main"))
        .isInstanceOf(SourceHostUnavailableException.class);
  }

  @Test
  void aForbiddenOrOtherwiseRefusedRequestIsARejection() {
    server.forcedStatus = 403;
    assertThatThrownBy(() -> client.branchHead(REPO, "main"))
        .isInstanceOf(SourceHostRejectedException.class);
    server.forcedStatus = 401;
    assertThatThrownBy(() -> client.branchHead(REPO, "main"))
        .isInstanceOf(SourceHostRejectedException.class);
  }

  @Test
  void aRedirectIsNotFollowedSoTheTokenCannotBeSentElsewhere() {
    server.forcedStatus = 302;
    server.forcedHeaderName = "Location";
    server.forcedHeaderValue = "http://127.0.0.1:1/elsewhere";

    assertThatThrownBy(() -> client.branchHead(REPO, "main"))
        .isInstanceOf(SourceHostRejectedException.class);
    assertThat(server.requests).hasSize(1);
  }

  @Test
  void aHostThatCannotBeReachedIsUnavailable() {
    server.close();

    assertThatThrownBy(() -> client.branchHead(REPO, "main"))
        .isInstanceOf(SourceHostUnavailableException.class);
  }

  @Test
  void aNameThatIsNotARepositoryIsRefusedBeforeAnyRequestIsMade() {
    assertThatThrownBy(() -> client.branchHead("../../etc/passwd", "main"))
        .isInstanceOf(SourceHostRejectedException.class);
    assertThatThrownBy(() -> client.branchHead("noslash", "main"))
        .isInstanceOf(SourceHostRejectedException.class);
    assertThat(server.requests).isEmpty();
  }
}
