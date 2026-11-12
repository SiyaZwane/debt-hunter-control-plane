package com.debthunter.controlplane.ai.remediation;

import static com.debthunter.controlplane.ai.AiFixtures.CLOCK;
import static com.debthunter.controlplane.ai.AiFixtures.hotspot;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ai.guard.DispatchRecord;
import com.debthunter.controlplane.ai.guard.FakeModelClient;
import com.debthunter.controlplane.ai.guard.InMemoryDispatchLog;
import com.debthunter.controlplane.ai.guard.PromptGuard;
import com.debthunter.controlplane.ai.guard.Purpose;
import com.debthunter.controlplane.ai.guard.SourceSharing;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-62: a remediation prompt never contains source code, even for a repository that has opted
 * in to sharing it for explanations, and the guard does not even ask for the source.
 */
class CpAc62_NoSourceInRemediationPromptTest {

  private final AtomicInteger sharingChecks = new AtomicInteger();
  private final AtomicInteger sourceFetches = new AtomicInteger();
  private final InMemoryDispatchLog log = new InMemoryDispatchLog();
  private final FakeModelClient model = new FakeModelClient();
  private final PromptGuard guard =
      new PromptGuard(
          repository -> {
            sharingChecks.incrementAndGet();
            return SourceSharing.OPTED_IN;
          },
          (repository, path, line) -> {
            sourceFetches.incrementAndGet();
            return Optional.of("int secret = readSecret(); // SOURCE-MARKER");
          },
          log,
          CLOCK);
  private final ModelRemediationAdvisor advisor = new ModelRemediationAdvisor(guard, model, CLOCK);

  @Test
  void cpAc62_anOptedInRepositoryStillGetsNoSourceInARemediationPrompt() {
    advisor.propose(new RemediationRequest("acme/widgets", "run-1", hotspot(), "main"));

    assertThat(model.lastPromptText()).doesNotContain("SOURCE-MARKER").doesNotContain("<source>");
    assertThat(model.received.get(0).sourceIncluded()).isFalse();
  }

  @Test
  void cpAc62_theSourceIsNeverFetchedAndTheOptInIsNeverEvenConsulted() {
    advisor.propose(new RemediationRequest("acme/widgets", "run-1", hotspot(), "main"));

    assertThat(sourceFetches).hasValue(0);
    assertThat(sharingChecks).hasValue(0);
  }

  @Test
  void cpAc62_theRecordSaysItWasARemediationDispatchWithNoSource() {
    advisor.propose(new RemediationRequest("acme/widgets", "run-1", hotspot(), "main"));

    DispatchRecord record = log.records().get(0);
    assertThat(record.purpose()).isEqualTo(Purpose.REMEDIATE);
    assertThat(record.sourceIncluded()).isFalse();
    assertThat(record.fieldsIncluded()).doesNotContain("source");
    assertThat(record.restrictedByUnknownChoice()).isFalse();
  }

  @Test
  void cpAc62_anExplanationForTheSameRepositoryStillGetsSourceSoTheDifferenceIsThePurpose() {
    guard.dispatch("acme/widgets", hotspot(), model);

    assertThat(model.lastPromptText()).contains("SOURCE-MARKER");
    assertThat(log.records().get(0).purpose()).isEqualTo(Purpose.EXPLAIN);
  }
}
