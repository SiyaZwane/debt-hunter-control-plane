package com.debthunter.controlplane.ai.explain;

import com.debthunter.controlplane.ai.guard.ModelClient;
import com.debthunter.controlplane.ai.guard.ModelResult;
import com.debthunter.controlplane.ai.guard.PromptGuard;
import java.time.Clock;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Explains a finding with a language model, always through the {@link PromptGuard}. Not wired into
 * the running application yet: it is what turning a model on will add to the explainer chain.
 *
 * <p>A model that is down, a dispatch that could not be recorded, or an empty answer all produce
 * {@code empty}, so the service falls through to the next explainer.
 */
public final class ModelExplainer implements Explainer {

  private static final Logger LOG = LoggerFactory.getLogger(ModelExplainer.class);
  private static final int MAX_TEXT = 3000;

  private final PromptGuard guard;
  private final ModelClient client;
  private final Clock clock;

  /**
   * Creates the explainer.
   *
   * @param guard the only way to reach the model
   * @param client the model
   * @param clock the source of "now" for the provenance
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public ModelExplainer(PromptGuard guard, ModelClient client, Clock clock) {
    this.guard = Objects.requireNonNull(guard, "guard");
    this.client = Objects.requireNonNull(client, "client");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Optional<Explanation> explain(ExplainRequest request) {
    try {
      ModelResult result = guard.dispatch(request.repository(), request.finding(), client);
      String text = result.text() == null ? "" : result.text().strip();
      if (text.isEmpty()) {
        return Optional.empty();
      }
      if (text.length() > MAX_TEXT) {
        text = text.substring(0, MAX_TEXT) + "…";
      }
      return Optional.of(
          ProvenanceLabeller.byModel(
              request.finding().fingerprint(),
              request.finding().ruleId(),
              text,
              result.modelVersion(),
              PromptGuard.PROMPT_VERSION,
              clock.instant(),
              result.sourceIncluded()));
    } catch (RuntimeException e) {
      LOG.warn("No model explanation for {}: {}", request.finding().id(), e.toString());
      return Optional.empty();
    }
  }

  @Override
  public boolean cacheable() {
    return true;
  }

  @Override
  public String generatorVersion() {
    return client.modelVersion() + "/" + PromptGuard.PROMPT_VERSION;
  }
}
