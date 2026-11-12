package com.debthunter.controlplane.ai.remediation;

import com.debthunter.controlplane.ai.explain.ProvenanceLabeller;
import com.debthunter.controlplane.ai.guard.ModelClient;
import com.debthunter.controlplane.ai.guard.ModelResult;
import com.debthunter.controlplane.ai.guard.PromptGuard;
import com.debthunter.controlplane.ai.guard.Purpose;
import com.debthunter.controlplane.ai.rules.RuleCatalogue;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Proposes next steps with a language model, always through the {@link PromptGuard} and always for
 * {@link Purpose#REMEDIATE}, so no source code is ever in the prompt. Not wired into the running
 * application: it is what turning a model on will add to the chain.
 */
public final class ModelRemediationAdvisor implements RemediationAdvisor {

  private static final Logger LOG = LoggerFactory.getLogger(ModelRemediationAdvisor.class);
  private static final int MAX_STEPS = 8;
  private static final int MAX_STEP_LENGTH = 300;

  private final PromptGuard guard;
  private final ModelClient client;
  private final Clock clock;

  /**
   * Creates the advisor.
   *
   * @param guard the only way to reach the model
   * @param client the model
   * @param clock the source of "now" for the provenance
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public ModelRemediationAdvisor(PromptGuard guard, ModelClient client, Clock clock) {
    this.guard = Objects.requireNonNull(guard, "guard");
    this.client = Objects.requireNonNull(client, "client");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Optional<RemediationProposal> propose(RemediationRequest request) {
    try {
      ModelResult result =
          guard.dispatch(Purpose.REMEDIATE, request.repository(), request.finding(), client);
      List<String> steps = parse(result.text());
      if (steps.isEmpty()) {
        return Optional.empty();
      }
      return Optional.of(
          new RemediationProposal(
              request.finding().fingerprint(),
              request.finding().ruleId(),
              RuleCatalogue.describe(request.finding().ruleId(), request.finding().category())
                  .title(),
              steps,
              ProvenanceLabeller.modelProvenance(
                  result.modelVersion(), PromptGuard.PROMPT_VERSION, clock.instant())));
    } catch (RuntimeException e) {
      LOG.warn("No model remediation for {}: {}", request.finding().id(), e.toString());
      return Optional.empty();
    }
  }

  /** Turns the model's text into steps: one per non-empty line, numbering and bullets removed. */
  static List<String> parse(String text) {
    List<String> steps = new ArrayList<>();
    if (text == null) {
      return steps;
    }
    for (String line : text.split("\\R")) {
      String step = line.strip().replaceFirst("^([-*•]|\\d+[.)])\\s*", "").strip();
      if (step.isEmpty()) {
        continue;
      }
      steps.add(step.length() > MAX_STEP_LENGTH ? step.substring(0, MAX_STEP_LENGTH) + "…" : step);
      if (steps.size() == MAX_STEPS) {
        break;
      }
    }
    return steps;
  }
}
