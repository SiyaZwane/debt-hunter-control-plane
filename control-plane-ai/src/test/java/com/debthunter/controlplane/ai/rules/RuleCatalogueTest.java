package com.debthunter.controlplane.ai.rules;

import static org.assertj.core.api.Assertions.assertThat;

import com.debthunter.domain.Category;
import org.junit.jupiter.api.Test;

class RuleCatalogueTest {

  @Test
  void everyCategoryGetsADescriptorEvenForAnUnknownRule() {
    for (Category category : Category.values()) {
      RuleDescriptor d = RuleCatalogue.describe("something.unknown", category);

      assertThat(d.title()).isNotBlank();
      assertThat(d.meaning()).contains("something.unknown");
      assertThat(d.impact()).isNotBlank();
      assertThat(d.lookAt()).isNotBlank();
    }
  }

  @Test
  void knownRulesDoNotFallBackToTheGenericWording() {
    for (String rule :
        new String[] {
          "codemaat.hotspot",
          "codemaat.churn",
          "codemaat.temporal-coupling",
          "codemaat.knowledge-concentration",
          "architecture.anything",
          "sonar.java:S1"
        }) {
      assertThat(RuleCatalogue.describe(rule, Category.CUSTOM).meaning())
          .as(rule)
          .doesNotContain("was raised by rule");
    }
  }
}
