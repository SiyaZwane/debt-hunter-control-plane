package com.debthunter.controlplane.ai.explain;

import com.debthunter.controlplane.ai.rules.RuleCatalogue;
import com.debthunter.controlplane.ai.rules.RuleDescriptor;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import java.time.Clock;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Explains a finding from a fixed template and the finding's own facts. No model is involved, so
 * nothing leaves the process and nothing is spent; the text is labelled as template-produced.
 *
 * <p>It only restates what the rule and the finding say. It uses the finding's message verbatim and
 * the evidence the control plane retains, and it makes no claim the finding does not support.
 */
public final class RuleBasedExplainer implements Explainer {

  /** The version of the template set; change it when the wording changes. */
  public static final String TEMPLATE_VERSION = "rule-templates-1";

  private static final int MAX_EVIDENCE_VALUE = 200;

  private final Clock clock;

  /**
   * Creates the explainer.
   *
   * @param clock the source of "now" for the provenance
   */
  public RuleBasedExplainer(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Optional<Explanation> explain(ExplainRequest request) {
    PseudonymisedFinding f = request.finding();
    RuleDescriptor d = RuleCatalogue.describe(f.ruleId(), f.category());
    StringBuilder text = new StringBuilder();
    text.append(d.title()).append(" (").append(f.severity()).append("): ").append(f.message());
    text.append("\n\nWhat this means: ").append(d.meaning());
    text.append("\nWhy it can matter: ").append(d.impact());
    text.append("\nWhere to start: ").append(d.lookAt());
    String evidence = evidence(f.evidence());
    if (!evidence.isEmpty()) {
      text.append("\n\nEvidence: ").append(evidence);
    }
    return Optional.of(
        ProvenanceLabeller.byTemplate(
            f.fingerprint(), f.ruleId(), text.toString(), TEMPLATE_VERSION, clock.instant()));
  }

  @Override
  public boolean cacheable() {
    return false;
  }

  @Override
  public String generatorVersion() {
    return TEMPLATE_VERSION;
  }

  private static String evidence(Map<String, Object> evidence) {
    StringBuilder sb = new StringBuilder();
    for (Map.Entry<String, Object> e : new TreeMap<>(evidence).entrySet()) {
      Object v = e.getValue();
      if (!(v instanceof String || v instanceof Number || v instanceof Boolean)) {
        continue;
      }
      String value = String.valueOf(v);
      if (value.length() > MAX_EVIDENCE_VALUE) {
        value = value.substring(0, MAX_EVIDENCE_VALUE) + "…";
      }
      if (sb.length() > 0) {
        sb.append("; ");
      }
      sb.append(e.getKey()).append(" = ").append(value);
    }
    return sb.toString();
  }
}
