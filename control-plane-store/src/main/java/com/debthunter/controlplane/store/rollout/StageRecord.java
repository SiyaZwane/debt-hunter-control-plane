package com.debthunter.controlplane.store.rollout;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Where one repository is in the rollout.
 *
 * @param repository the repository
 * @param stage its current stage
 * @param effectiveFrom when it entered that stage
 * @param owner the named person or team accountable for its progress
 * @param exitEvidence the criteria that were satisfied to enter this stage, each with the evidence
 *     that satisfied it; empty for a repository that was enrolled at stage 1 or moved back
 * @param exemption an approved exemption from the maximum hold, or {@code null}
 * @param escalatedAt when this stay was escalated for exceeding the maximum hold, or {@code null}
 */
public record StageRecord(
    String repository,
    RolloutStage stage,
    Instant effectiveFrom,
    String owner,
    Map<String, String> exitEvidence,
    StageExemption exemption,
    Instant escalatedAt) {

  /** Validates and copies the components. */
  public StageRecord {
    if (repository == null || repository.isBlank()) {
      throw new IllegalArgumentException("A stage record needs a repository");
    }
    Objects.requireNonNull(stage, "stage");
    Objects.requireNonNull(effectiveFrom, "effectiveFrom");
    if (owner == null || owner.isBlank()) {
      throw new IllegalArgumentException("A repository in the rollout needs a named owner");
    }
    exitEvidence =
        exitEvidence == null
            ? Map.of()
            : java.util.Collections.unmodifiableMap(new TreeMap<>(exitEvidence));
  }

  /** How long it has been at this stage. */
  public Duration heldFor(Instant now) {
    return Duration.between(effectiveFrom, now);
  }

  /** A copy carrying an exemption. */
  public StageRecord withExemption(StageExemption granted) {
    return new StageRecord(
        repository, stage, effectiveFrom, owner, exitEvidence, granted, escalatedAt);
  }

  /** A copy marked as escalated. */
  public StageRecord escalated(Instant at) {
    return new StageRecord(repository, stage, effectiveFrom, owner, exitEvidence, exemption, at);
  }
}
