package com.debthunter.controlplane.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ai.explain.ExplainFixture;
import com.debthunter.controlplane.ai.explain.Explanation;
import com.debthunter.controlplane.ai.guard.DispatchRecord;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-54: a repository that opted in has source included, and that fact is on the record: which
 * repository, which finding, which fields, which model.
 */
@Tag("integration")
class CpAc54_SourceIncludedWithOptInLoggedTest {

  private final ExplainFixture f = new ExplainFixture();

  @Test
  void anOptedInRepositoryHasSourceSentAndTheDispatchLogged() {
    f.guard.optedIn("acme/a");

    f.withModel.explain(ExplainFixture.request("acme/a"));

    assertThat(f.guard.model.lastPromptText()).contains("SOURCE-MARKER");
    assertThat(f.guard.log.records()).hasSize(1);
    DispatchRecord record = f.guard.log.records().get(0);
    assertThat(record.repository()).isEqualTo("acme/a");
    assertThat(record.findingIds()).containsExactly(AiFixtures.hotspot().id());
    assertThat(record.fieldsIncluded()).contains("source", "message", "path");
    assertThat(record.modelVersion()).isEqualTo("fake-model-1");
    assertThat(record.at()).isEqualTo(AiFixtures.NOW);
  }

  @Test
  void everyDispatchIsLoggedNotJustTheOnesWithSource() {
    f.withModel.explain(ExplainFixture.request("acme/b"));
    f.guard.optedIn("acme/a");
    f.withModel.explain(
        new com.debthunter.controlplane.ai.explain.ExplainRequest(
            "acme/a", AiFixtures.withMessage("other finding")));

    assertThat(f.guard.log.records())
        .extracting(DispatchRecord::sourceIncluded)
        .containsExactly(false, true);
  }

  @Test
  void textWrittenFromSourceIsMarkedSoAndIsNotKeptForOthers() {
    f.guard.optedIn("acme/a");

    Explanation e = f.withModel.explain(ExplainFixture.request("acme/a")).orElseThrow();

    assertThat(e.sourceInformed()).isTrue();
    assertThat(f.cache.size()).isZero();
  }
}
