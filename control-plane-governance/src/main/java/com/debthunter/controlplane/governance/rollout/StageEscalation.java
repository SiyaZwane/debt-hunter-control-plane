package com.debthunter.controlplane.governance.rollout;

import com.debthunter.controlplane.store.rollout.RolloutStage;
import java.time.Duration;
import java.time.Instant;

/**
 * A repository has been at a stage longer than allowed, with no exemption to excuse it.
 *
 * @param repository the repository
 * @param stage the stage it is stuck at
 * @param owner who is accountable for moving it
 * @param heldSince when it entered the stage
 * @param heldFor how long it has been there
 * @param maxHold how long it was allowed
 */
public record StageEscalation(
    String repository,
    RolloutStage stage,
    String owner,
    Instant heldSince,
    Duration heldFor,
    Duration maxHold) {}
