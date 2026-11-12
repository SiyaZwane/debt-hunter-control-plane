package com.debthunter.controlplane.egress.digest;

import java.util.Objects;

/**
 * Where a squad is told. The webhook address is a secret, so only a reference to it is kept here.
 *
 * @param owner the squad (a repository owner)
 * @param kind which chat product
 * @param secretRef the name under which the webhook address is held
 */
public record SquadChannel(String owner, Kind kind, String secretRef) {

  /** The chat products there are adapters for. */
  public enum Kind {
    /** A Slack incoming webhook. */
    SLACK,
    /** A Teams incoming webhook. */
    TEAMS
  }

  /** Validates the components. */
  public SquadChannel {
    Objects.requireNonNull(owner, "owner");
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(secretRef, "secretRef");
    if (owner.isBlank() || secretRef.isBlank()) {
      throw new IllegalArgumentException("A channel needs an owner and a secret reference");
    }
  }
}
