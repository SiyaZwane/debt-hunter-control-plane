package com.debthunter.controlplane.governance.policy;

import java.util.Objects;

/**
 * One thing wrong, or doubtful, about a candidate policy bundle.
 *
 * <p>An {@link Level#ERROR} means the bundle cannot be trusted as written and nothing is simulated.
 * A {@link Level#WARNING} means the bundle is well formed but probably does not do what its author
 * intends; the author is told, and the simulation still runs, because the simulation is how they
 * find out how bad it is.
 *
 * @param level whether this stops the simulation
 * @param code a stable machine-readable identifier, for example {@code DUPLICATE_RULE_ID}
 * @param message what is wrong, in terms the author can act on
 */
public record PolicyIssue(Level level, String code, String message) {

  /** How serious an issue is. */
  public enum Level {
    ERROR,
    WARNING
  }

  /** Validates required fields. */
  public PolicyIssue {
    Objects.requireNonNull(level, "level");
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(message, "message");
  }

  /**
   * An error.
   *
   * @param code the stable identifier
   * @param message what is wrong
   * @return the issue
   */
  public static PolicyIssue error(String code, String message) {
    return new PolicyIssue(Level.ERROR, code, message);
  }

  /**
   * A warning.
   *
   * @param code the stable identifier
   * @param message what is doubtful
   * @return the issue
   */
  public static PolicyIssue warning(String code, String message) {
    return new PolicyIssue(Level.WARNING, code, message);
  }
}
