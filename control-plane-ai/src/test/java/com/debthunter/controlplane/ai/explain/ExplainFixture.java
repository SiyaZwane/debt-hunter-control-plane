package com.debthunter.controlplane.ai.explain;

import com.debthunter.controlplane.ai.AiFixtures;
import com.debthunter.controlplane.ai.guard.GuardFixture;
import java.util.List;

/** A service with a model chain (model first, templates as the floor) over controllable parts. */
public final class ExplainFixture {

  public final GuardFixture guard = new GuardFixture();
  public final InMemoryExplanationCache cache = new InMemoryExplanationCache();
  public final ModelExplainer model =
      new ModelExplainer(guard.guard, guard.model, AiFixtures.CLOCK);
  public final RuleBasedExplainer templates = new RuleBasedExplainer(AiFixtures.CLOCK);
  public final ExplanationService withModel =
      new ExplanationService(List.of(model, templates), cache);
  public final ExplanationService templatesOnly = new ExplanationService(List.of(templates), cache);

  public static ExplainRequest request(String repository) {
    return new ExplainRequest(repository, AiFixtures.hotspot());
  }
}
