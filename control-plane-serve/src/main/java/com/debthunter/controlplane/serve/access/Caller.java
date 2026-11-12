package com.debthunter.controlplane.serve.access;

import java.util.Set;

/**
 * Who is asking, as the services see them: the token's subject, the roles it carries, and the
 * squads (repository owners) it belongs to. Every service method takes one and checks it, so access
 * does not depend on a controller having remembered to.
 *
 * @param subject the token's {@code sub}
 * @param roles the token's {@code roles}
 * @param squads the token's {@code squads}
 */
public record Caller(String subject, Set<String> roles, Set<String> squads) {

  /** Validates and copies the components. */
  public Caller {
    if (subject == null || subject.isBlank()) {
      throw new IllegalArgumentException("A caller needs a subject");
    }
    roles = roles == null ? Set.of() : Set.copyOf(roles);
    squads = squads == null ? Set.of() : Set.copyOf(squads);
  }

  /** Whether the caller holds a role. */
  public boolean has(String role) {
    return roles.contains(role);
  }

  /** Whether the caller belongs to a squad. */
  public boolean isIn(String squad) {
    return squads.contains(squad);
  }
}
