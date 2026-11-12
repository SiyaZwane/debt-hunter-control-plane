package com.debthunter.controlplane.serve.finding;

import com.debthunter.controlplane.ai.explain.Explanation;
import java.time.Instant;

/**
 * A finding with its explanation as a client receives it: plain data, with who or what wrote the
 * explanation always stated.
 *
 * @param repository the repository
 * @param runId the run that reported the finding
 * @param finding the finding
 * @param explanation its explanation, or null if none could be produced (the finding is shown
 *     either way)
 */
public record FindingDetailView(
    String repository, String runId, FindingView finding, ExplanationView explanation) {

  /**
   * An explanation and where it came from.
   *
   * @param text the explanation
   * @param modelGenerated whether a language model wrote it; false means a fixed template did
   * @param source {@code MODEL} or {@code RULE_TEMPLATE}
   * @param generator the model or template set that produced it
   * @param generatorVersion its version
   * @param generatedAt when it was produced
   */
  public record ExplanationView(
      String text,
      boolean modelGenerated,
      String source,
      String generator,
      String generatorVersion,
      Instant generatedAt) {

    static ExplanationView of(Explanation e) {
      return new ExplanationView(
          e.text(),
          e.modelGenerated(),
          e.provenance().source().name(),
          e.provenance().generator(),
          e.provenance().generatorVersion(),
          e.provenance().generatedAt());
    }
  }

  /** The client view of a finding detail. */
  public static FindingDetailView of(FindingDetail d) {
    return new FindingDetailView(
        d.repository(),
        d.runId(),
        d.finding(),
        d.explanation().map(ExplanationView::of).orElse(null));
  }
}
