package com.debthunter.controlplane.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.ai.explain.ExplainFixture;
import com.debthunter.controlplane.ai.explain.Explanation;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-55: an explanation is cached by what was explained, so a second repository with the same
 * finding costs no model call. (The cache here is in memory; Redis arrives with the model.)
 */
@Tag("integration")
class CpAc55_ExplanationCachedAcrossReposTest {

  private final ExplainFixture f = new ExplainFixture();

  @Test
  void manyRepositoriesAskingForTheSameFindingCostOneModelCall() {
    Explanation first = null;
    for (String repository : new String[] {"acme/a", "acme/b", "acme/c", "other/d"}) {
      Explanation e = f.withModel.explain(ExplainFixture.request(repository)).orElseThrow();
      first = first == null ? e : first;
      assertThat(e).isEqualTo(first);
    }

    assertThat(f.guard.model.calls()).isEqualTo(1);
    assertThat(f.guard.log.records()).as("only the one dispatch was logged").hasSize(1);
  }

  @Test
  void theCachedExplanationNeverMentionsTheRepositoryThatCausedItToBeMade() {
    f.withModel.explain(ExplainFixture.request("acme/secret-project"));

    assertThat(f.guard.model.lastPromptText()).doesNotContain("secret-project");
  }
}
