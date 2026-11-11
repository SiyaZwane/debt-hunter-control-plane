package com.debthunter.controlplane.store.rollout;

import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Something that happened to a repository's place in the rollout. The log of these is append-only.
 *
 * @param repository the repository
 * @param kind what happened
 * @param fromStage the stage it was at, or {@code null} when it was enrolled
 * @param toStage the stage it is at now
 * @param at when
 * @param actor the approving authority
 * @param criteria the exit criteria satisfied, each with its evidence (advances only)
 * @param reason why (regressions and exemptions)
 */
public record StageEvent(
    String repository,
    Kind kind,
    RolloutStage fromStage,
    RolloutStage toStage,
    Instant at,
    String actor,
    Map<String, String> criteria,
    String reason) {

  /** What happened. */
  public enum Kind {
    /** The repository entered the rollout, at stage 1. */
    ENROLLED,
    /** It moved forward one stage. */
    ADVANCED,
    /** It moved back. */
    REGRESSED,
    /** An exemption from the maximum hold was approved. */
    EXEMPTION_GRANTED
  }

  /** Validates and copies the components. */
  public StageEvent {
    if (repository == null || repository.isBlank()) {
      throw new IllegalArgumentException("A stage event needs a repository");
    }
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(toStage, "toStage");
    Objects.requireNonNull(at, "at");
    if (actor == null || actor.isBlank()) {
      throw new IllegalArgumentException("A stage event needs an approving authority");
    }
    criteria = criteria == null ? Map.of() : Collections.unmodifiableMap(new TreeMap<>(criteria));
  }
}
