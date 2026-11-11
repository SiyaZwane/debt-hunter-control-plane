package com.debthunter.controlplane.ai.explain;

import static com.debthunter.controlplane.ai.AiFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/**
 * CP-AC-56: generated text says who wrote it. A model's text is marked as model-generated; text
 * from a template is marked as not, and no explanation can exist without a label.
 */
class CpAc56_GeneratedTextLabelledTest {

  @Test
  void cpAc56_modelTextCarriesModelGeneratedTrue() {
    Explanation e = ProvenanceLabeller.byModel("fp", "r", "text", "gpt-x", "prompt-1", NOW, false);

    assertThat(e.modelGenerated()).isTrue();
    assertThat(e.provenance().source()).isEqualTo(Provenance.Source.MODEL);
  }

  @Test
  void cpAc56_templateTextCarriesModelGeneratedFalse() {
    Explanation e = ProvenanceLabeller.byTemplate("fp", "r", "text", "rule-templates-1", NOW);

    assertThat(e.modelGenerated()).isFalse();
    assertThat(e.provenance().source()).isEqualTo(Provenance.Source.RULE_TEMPLATE);
  }

  @Test
  void cpAc56_whetherAModelWroteItCannotDisagreeWithItsSource() {
    for (Provenance.Source source : Provenance.Source.values()) {
      Provenance p = new Provenance(source, "g", "v", NOW);

      assertThat(p.modelGenerated()).isEqualTo(source == Provenance.Source.MODEL);
    }
  }

  @Test
  void cpAc56_noExplanationCanBeBuiltWithoutThroughTheLabeller() {
    assertThat(Arrays.stream(Explanation.class.getDeclaredConstructors()))
        .noneMatch(
            c -> Modifier.isPublic(c.getModifiers()) || Modifier.isProtected(c.getModifiers()));
  }
}
