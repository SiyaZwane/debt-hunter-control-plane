package com.debthunter.controlplane.api.admin;

import java.time.Instant;
import java.util.Objects;

/**
 * One line of the administration audit trail.
 *
 * @param caller the authenticated administrator (the token's {@code sub})
 * @param action what they did
 * @param target what it was done to
 * @param at when
 */
public record AdminAuditEntry(String caller, String action, String target, Instant at) {

  /** Validates the components. */
  public AdminAuditEntry {
    Objects.requireNonNull(caller, "caller");
    Objects.requireNonNull(action, "action");
    Objects.requireNonNull(target, "target");
    Objects.requireNonNull(at, "at");
  }
}
