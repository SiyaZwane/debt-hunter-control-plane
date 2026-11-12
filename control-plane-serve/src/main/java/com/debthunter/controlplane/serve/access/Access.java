package com.debthunter.controlplane.serve.access;

import java.util.Objects;

/** The checks every service makes before it reads anything. */
public final class Access {

  private Access() {}

  /**
   * Requires a role.
   *
   * @param caller who is asking
   * @param role the role needed
   * @throws AccessDeniedException if the caller lacks it
   */
  public static void requireRole(Caller caller, String role) {
    Objects.requireNonNull(caller, "caller");
    if (!caller.has(role)) {
      throw new AccessDeniedException("That needs the " + role + " role");
    }
  }

  /**
   * Requires a role and membership of a squad.
   *
   * @param caller who is asking
   * @param role the role needed
   * @param squad the squad whose data is wanted
   * @throws AccessDeniedException if the caller lacks the role or is not in the squad
   */
  public static void requireSquad(Caller caller, String role, String squad) {
    requireRole(caller, role);
    if (squad == null || !caller.isIn(squad)) {
      throw new AccessDeniedException("The caller does not belong to that squad");
    }
  }
}
