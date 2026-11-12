package com.debthunter.controlplane.ai.remediation;

import com.debthunter.controlplane.ai.explain.ProvenanceLabeller;
import com.debthunter.controlplane.ai.rules.RuleCatalogue;
import com.debthunter.controlplane.ai.rules.RuleDescriptor;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.domain.Category;
import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Proposes next steps from the finding's category and rule, with no model. The steps are fixed
 * wording that names nothing from the repository, so nothing a repository controls can reach the
 * text of the proposal.
 */
public final class RuleBasedRemediationAdvisor implements RemediationAdvisor {

  /** The version of the template set; change it when the wording changes. */
  public static final String TEMPLATE_VERSION = "remediation-templates-1";

  private final Clock clock;

  /**
   * Creates the advisor.
   *
   * @param clock the source of "now" for the provenance
   */
  public RuleBasedRemediationAdvisor(Clock clock) {
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  @Override
  public Optional<RemediationProposal> propose(RemediationRequest request) {
    PseudonymisedFinding f = request.finding();
    RuleDescriptor descriptor = RuleCatalogue.describe(f.ruleId(), f.category());
    return Optional.of(
        new RemediationProposal(
            f.fingerprint(),
            f.ruleId(),
            descriptor.title(),
            steps(f.category(), descriptor),
            ProvenanceLabeller.templateProvenance(TEMPLATE_VERSION, clock.instant())));
  }

  private static List<String> steps(Category category, RuleDescriptor d) {
    return switch (category) {
      case HOTSPOT ->
          List.of(
              "Add characterisation tests around this file before changing it.",
              "Extract the logic that changes most often into a smaller, focused unit.",
              d.lookAt());
      case CHURN ->
          List.of(
              "Look at why this file changes so often and whether the changes share a cause.",
              "Consider splitting it along its independent responsibilities.");
      case TEMPORAL_COUPLING ->
          List.of(
              "Find what links the files that change together.",
              "Consider making that link explicit with a shared interface, or merging the files.");
      case KNOWLEDGE_CONCENTRATION ->
          List.of(
              "Pair on this file with another contributor to spread ownership.",
              "Add notes on how it works where the next person will find them.");
      case ARCHITECTURE ->
          List.of(
              "Remove or redirect the dependency the architecture rule forbids.",
              "If the boundary is meant to move, change the rule deliberately and record why.");
      case STATIC_ANALYSIS ->
          List.of(
              "Read the rule's documentation in the analysis tool.",
              "Fix the code, or record why this occurrence is acceptable.");
      case DEPENDENCY ->
          List.of(
              "Review the flagged dependency and whether it is still needed.",
              "Update it, or replace it, behind a small wrapper that limits the blast radius.");
      case TEST_HEALTH ->
          List.of(
              "Improve the coverage or reliability of the tests around this file.",
              "Fix or remove tests that fail intermittently rather than re-running them.");
      case CUSTOM ->
          List.of(
              d.lookAt(),
              "Decide whether the finding is worth acting on, and record why not if not.");
    };
  }
}
