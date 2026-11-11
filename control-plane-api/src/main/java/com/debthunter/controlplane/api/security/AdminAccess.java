package com.debthunter.controlplane.api.security;

import java.util.Objects;

/**
 * Which role in a token grants administration of the control plane.
 *
 * @param role the value the token's {@code roles} claim must contain
 */
public record AdminAccess(String role) {

  /** Validates the role. */
  public AdminAccess {
    Objects.requireNonNull(role, "role");
    if (role.isBlank()) {
      throw new IllegalArgumentException("The administrator role must not be blank");
    }
  }
}
