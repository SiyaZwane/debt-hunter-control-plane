package com.debthunter.controlplane.ai.explain;

import java.time.Instant;
import java.util.Objects;

/**
 * Who or what produced a piece of text. Whether a model wrote it is derived from the source, never
 * set separately, so it cannot disagree with it.
 *
 * @param source how the text was produced
 * @param generator what produced it: the model name, or the template set
 * @param generatorVersion the version of that generator (for a model, including the prompt version)
 * @param generatedAt when it was produced
 */
public record Provenance(
    Source source, String generator, String generatorVersion, Instant generatedAt) {

  /** How a piece of text was produced. */
  public enum Source {
    /** Written by a language model. */
    MODEL,
    /** Filled in from a fixed template; no model was involved. */
    RULE_TEMPLATE
  }

  /** Validates the components. */
  public Provenance {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(generator, "generator");
    Objects.requireNonNull(generatorVersion, "generatorVersion");
    Objects.requireNonNull(generatedAt, "generatedAt");
  }

  /** Whether a language model wrote the text. */
  public boolean modelGenerated() {
    return source == Source.MODEL;
  }
}
