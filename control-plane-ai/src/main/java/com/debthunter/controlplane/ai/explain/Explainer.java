package com.debthunter.controlplane.ai.explain;

import java.util.Optional;

/**
 * One way of explaining a finding. The service tries explainers in order and uses the first that
 * produces something.
 *
 * <p>Failure contract: an explainer never throws for an expected failure (a model that is down, a
 * refusal); it returns empty and the service moves on.
 */
public interface Explainer {

  /**
   * Explains a finding.
   *
   * @param request what to explain
   * @return the explanation, or empty if this explainer cannot produce one now
   */
  Optional<Explanation> explain(ExplainRequest request);

  /**
   * Whether its output is worth caching. True for anything that costs something to produce; false
   * for output that is cheap and deterministic.
   */
  boolean cacheable();

  /** Identifies what produces the output, so a change invalidates cached results. */
  String generatorVersion();
}
