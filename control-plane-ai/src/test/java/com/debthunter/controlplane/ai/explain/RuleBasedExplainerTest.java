package com.debthunter.controlplane.ai.explain;

import static com.debthunter.controlplane.ai.AiFixtures.CLOCK;
import static com.debthunter.controlplane.ai.AiFixtures.CONTRIBUTOR;
import static com.debthunter.controlplane.ai.AiFixtures.finding;
import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuleBasedExplainerTest {

  private final RuleBasedExplainer explainer = new RuleBasedExplainer(CLOCK);

  private String explain(PseudonymisedFinding f) {
    return explainer.explain(new ExplainRequest("acme/a", f)).orElseThrow().text();
  }

  private static PseudonymisedFinding of(
      String ruleId, Category category, String message, Map<String, Object> evidence) {
    return finding("id", ruleId, category, Severity.MEDIUM, "src/A.java", message, evidence, "fp");
  }

  @Test
  void everyDataPlaneRuleFamilyHasItsOwnWording() {
    assertThat(explain(of("codemaat.hotspot", Category.HOTSPOT, "m", Map.of())))
        .contains("Change hotspot");
    assertThat(explain(of("codemaat.churn", Category.CHURN, "m", Map.of()))).contains("High churn");
    assertThat(explain(of("codemaat.temporal-coupling", Category.TEMPORAL_COUPLING, "m", Map.of())))
        .contains("change together");
    assertThat(
            explain(
                of(
                    "codemaat.knowledge-concentration",
                    Category.KNOWLEDGE_CONCENTRATION,
                    "m",
                    Map.of())))
        .contains("Knowledge concentrated");
    assertThat(explain(of("architecture.no-domain-to-infra", Category.ARCHITECTURE, "m", Map.of())))
        .contains("Architecture rule violation");
    assertThat(explain(of("sonar.java:S1234", Category.STATIC_ANALYSIS, "m", Map.of())))
        .contains("Static analysis issue")
        .contains("java:S1234");
  }

  @Test
  void anUnknownRuleIsDescribedByItsCategoryRatherThanLeftBlank() {
    String text = explain(of("custom.thing", Category.CUSTOM, "something happened", Map.of()));

    assertThat(text).contains("Custom").contains("custom.thing").contains("something happened");
  }

  @Test
  void theFindingsOwnMessageAndSeverityAreStatedVerbatim() {
    String text =
        explain(
            of(
                "codemaat.hotspot",
                Category.HOTSPOT,
                "src/A.java is a change hotspot (31 revisions)",
                Map.of()));

    assertThat(text).contains("src/A.java is a change hotspot (31 revisions)").contains("MEDIUM");
  }

  @Test
  void retainedEvidenceIsListedInAStableOrderAndNonScalarsAreSkipped() {
    String text =
        explain(
            of(
                "architecture.layers",
                Category.ARCHITECTURE,
                "m",
                Map.of(
                    "rule",
                    "layers",
                    "import",
                    "com.x.Infra",
                    "nested",
                    Map.of("a", "b"),
                    "list",
                    List.of(1))));

    assertThat(text)
        .contains("Evidence: import = com.x.Infra; rule = layers")
        .doesNotContain("nested");
  }

  @Test
  void aFindingWithNoEvidenceHasNoEvidenceLine() {
    assertThat(explain(of("codemaat.churn", Category.CHURN, "m", Map.of())))
        .doesNotContain("Evidence:");
  }

  @Test
  void aVeryLongEvidenceValueIsShortened() {
    String text =
        explain(of("codemaat.churn", Category.CHURN, "m", Map.of("calculation", "x".repeat(1000))));

    assertThat(text.length()).isLessThan(900);
  }

  @Test
  void contributorsAndFingerprintsAreNeverInTheText() {
    String text = explain(of("codemaat.hotspot", Category.HOTSPOT, "m", Map.of()));

    assertThat(text).doesNotContain(CONTRIBUTOR).doesNotContain("fp");
  }

  @Test
  void itIsNotCacheableAndSaysWhichTemplateSetWroteIt() {
    assertThat(explainer.cacheable()).isFalse();
    assertThat(explainer.generatorVersion()).isEqualTo(RuleBasedExplainer.TEMPLATE_VERSION);
  }
}
