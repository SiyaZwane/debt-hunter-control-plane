package com.debthunter.controlplane.ai.explain;

import java.util.Optional;

/**
 * A shared cache of explanations, keyed by what was explained, not by who asked. Only text that
 * cannot quote a repository's source is ever put here.
 *
 * <p>Failure contract: the cache is an optimisation. A failing {@code get} is treated as a miss and
 * a failing {@code put} is ignored, so a broken cache can cost a model call but never an answer.
 */
public interface ExplanationCache {

  /**
   * What an entry is keyed by.
   *
   * @param fingerprint the finding's fingerprint
   * @param ruleId the finding's rule
   * @param generatorVersion what produced it, so a new model or prompt does not reuse old text
   */
  record Key(String fingerprint, String ruleId, String generatorVersion) {}

  /** Looks an explanation up. */
  Optional<Explanation> get(Key key);

  /** Stores an explanation. */
  void put(Key key, Explanation explanation);
}
