package com.debthunter.controlplane.ai.explain;

import java.time.Instant;

/**
 * Attaches authorship to generated text. It is the only way to make an {@link Explanation}, so
 * everything the control plane generates says whether a model wrote it.
 */
public final class ProvenanceLabeller {

  private ProvenanceLabeller() {}

  /**
   * The provenance of text written by a model.
   *
   * @param model the model's name and version
   * @param promptVersion the version of the prompt it was given
   * @param at when it was produced
   * @return the provenance
   */
  public static Provenance modelProvenance(String model, String promptVersion, Instant at) {
    return new Provenance(Provenance.Source.MODEL, model, promptVersion, at);
  }

  /**
   * The provenance of text filled in from a template.
   *
   * @param templateVersion the version of the template set
   * @param at when it was produced
   * @return the provenance
   */
  public static Provenance templateProvenance(String templateVersion, Instant at) {
    return new Provenance(Provenance.Source.RULE_TEMPLATE, "rule-templates", templateVersion, at);
  }

  /**
   * Labels text written by a model.
   *
   * @param fingerprint the finding's fingerprint
   * @param ruleId the finding's rule
   * @param text the model's text
   * @param model the model's name and version
   * @param promptVersion the version of the prompt it was given
   * @param at when it was produced
   * @param sourceInformed whether the prompt contained source code
   * @return the labelled explanation, with {@code modelGenerated} true
   */
  public static Explanation byModel(
      String fingerprint,
      String ruleId,
      String text,
      String model,
      String promptVersion,
      Instant at,
      boolean sourceInformed) {
    return new Explanation(
        fingerprint,
        ruleId,
        text,
        new Provenance(Provenance.Source.MODEL, model, promptVersion, at),
        sourceInformed);
  }

  /**
   * Labels text filled in from a template.
   *
   * @param fingerprint the finding's fingerprint
   * @param ruleId the finding's rule
   * @param text the text
   * @param templateVersion the version of the template set
   * @param at when it was produced
   * @return the labelled explanation, with {@code modelGenerated} false
   */
  public static Explanation byTemplate(
      String fingerprint, String ruleId, String text, String templateVersion, Instant at) {
    return new Explanation(
        fingerprint,
        ruleId,
        text,
        new Provenance(Provenance.Source.RULE_TEMPLATE, "rule-templates", templateVersion, at),
        false);
  }
}
