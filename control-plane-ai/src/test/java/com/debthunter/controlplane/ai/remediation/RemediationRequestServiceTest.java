package com.debthunter.controlplane.ai.remediation;

import static com.debthunter.controlplane.ai.remediation.RemediationFixture.REPO;
import static com.debthunter.controlplane.ai.remediation.RemediationFixture.finding;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ai.AiFixtures;
import com.debthunter.controlplane.ai.remediation.RequestOutcome.Reason;
import com.debthunter.controlplane.store.queue.QueuedItem;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RemediationRequestServiceTest {

  private final RemediationFixture f = new RemediationFixture();

  private RequestOutcome ask(String repository, String runId, String findingId, String base) {
    return f.requests.request(repository, runId, findingId, base, "alice");
  }

  private static Reason refusedReason(RequestOutcome outcome) {
    assertThat(outcome).isInstanceOf(RequestOutcome.Refused.class);
    return ((RequestOutcome.Refused) outcome).reason();
  }

  @Test
  void aRepositoryThatHasNotOptedInIsRefused() {
    f.enabledRepositories.clear();
    f.storeRun("run-1", REPO, finding(0));

    assertThat(refusedReason(ask(REPO, "run-1", finding(0).id(), "main")))
        .isEqualTo(Reason.NOT_ENABLED);
    assertThat(f.queue.counts(RemediationQueue.KIND).pending()).isZero();
  }

  @Test
  void anOptInThatCannotBeCheckedIsRefusedNotAssumed() {
    f.storeRun("run-1", REPO, finding(0));
    f.enablementDown = true;

    assertThat(refusedReason(ask(REPO, "run-1", finding(0).id(), "main")))
        .isEqualTo(Reason.ENABLEMENT_UNAVAILABLE);
  }

  @Test
  void aRunOfAnotherRepositoryOrAMissingFindingIsNotFound() {
    f.storeRun("run-1", "acme/other", finding(0));
    f.storeRun("run-2", REPO, finding(1));

    assertThat(refusedReason(ask(REPO, "run-1", finding(0).id(), "main")))
        .isEqualTo(Reason.NOT_FOUND);
    assertThat(refusedReason(ask(REPO, "run-2", "no-such-finding", "main")))
        .isEqualTo(Reason.NOT_FOUND);
    assertThat(refusedReason(ask(REPO, "no-such-run", finding(1).id(), "main")))
        .isEqualTo(Reason.NOT_FOUND);
  }

  @Test
  void aBaseBranchThatIsNotAPlainBranchNameIsRefused() {
    f.storeRun("run-1", REPO, finding(0));

    for (String bad :
        new String[] {null, "", "../main", "ma in", "main;rm", "-x", "a".repeat(101)}) {
      assertThat(refusedReason(ask(REPO, "run-1", finding(0).id(), bad)))
          .as(String.valueOf(bad))
          .isEqualTo(Reason.INVALID_BASE_BRANCH);
    }
    assertThat(ask(REPO, "run-1", finding(0).id(), "release/2.x"))
        .isInstanceOf(RequestOutcome.Queued.class);
  }

  @Test
  void theQueuedRequestHoldsIdentifiersOnlyNeverFindingContentOrContributors() {
    f.storeRun("run-1", REPO, finding(0));
    ask(REPO, "run-1", finding(0).id(), "main");

    QueuedItem item = f.queue.claim(RemediationQueue.KIND, "w", 1).get(0);
    String payload = new String(item.payload(), StandardCharsets.UTF_8);

    assertThat(payload).contains(finding(0).id()).contains("run-1").contains("alice");
    assertThat(payload)
        .doesNotContain(AiFixtures.CONTRIBUTOR)
        .doesNotContain("fp-0")
        .doesNotContain("change hotspot")
        .doesNotContain("changeFrequency");
  }

  @Test
  void theJobRoundTripsAndRefusesIncompleteOrGarbageInput() {
    RemediationJob job = new RemediationJob(REPO, "run-1", "f1", "main", "alice");

    assertThat(RemediationJob.decode(job.encode())).isEqualTo(job);
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> RemediationJob.decode("{\"repository\":\"x\"}".getBytes()));
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> RemediationJob.decode("nope".getBytes()));
  }

  @Test
  void aRateLimitNeedsSensibleNumbers() {
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new RemediationRateLimit(
                    0, java.time.Duration.ofDays(1), java.time.Duration.ofHours(1)));
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                new RemediationRateLimit(
                    1, java.time.Duration.ZERO, java.time.Duration.ofHours(1)));
  }

  @Test
  void runLinksNeedAnAddressAndEncodeTheRunId() {
    assertThat(new ConfiguredRunLinks("https://cp.example/").linkTo("run 1/x").orElseThrow())
        .isEqualTo("https://cp.example/runs/run+1%2Fx");
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> new ConfiguredRunLinks("ftp://x"));
    assertThat(RunLinks.none().linkTo("r")).isEmpty();
  }
}
