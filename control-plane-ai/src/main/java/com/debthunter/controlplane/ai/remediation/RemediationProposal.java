package com.debthunter.controlplane.ai.remediation;

import com.debthunter.controlplane.ai.explain.Provenance;
import java.util.List;
import java.util.Objects;

/**
 * Suggested next steps for a finding, with the provenance of the text. A proposal is advice for a
 * person to weigh: it contains no code and is never applied.
 *
 * @param fingerprint the finding's fingerprint
 * @param ruleId the finding's rule
 * @param title a short name for the proposal
 * @param steps the suggested steps, in order
 * @param provenance who or what wrote them
 */
public record RemediationProposal(
    String fingerprint, String ruleId, String title, List<String> steps, Provenance provenance) {

  /** Validates and copies the components. */
  public RemediationProposal {
    Objects.requireNonNull(fingerprint, "fingerprint");
    Objects.requireNonNull(ruleId, "ruleId");
    Objects.requireNonNull(title, "title");
    Objects.requireNonNull(provenance, "provenance");
    steps = List.copyOf(steps);
    if (steps.isEmpty()) {
      throw new IllegalArgumentException("A proposal needs at least one step");
    }
  }

  /** Whether a language model wrote it. */
  public boolean modelGenerated() {
    return provenance.modelGenerated();
  }
}
