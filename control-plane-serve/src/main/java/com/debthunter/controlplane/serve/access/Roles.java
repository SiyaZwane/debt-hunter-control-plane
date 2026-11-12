package com.debthunter.controlplane.serve.access;

/** The roles a token can carry for reading the control plane. */
public final class Roles {

  /** May see estate-level numbers grouped by team, and nothing finer. */
  public static final String LEADER = "ControlPlane.Leader";

  /** May see the findings of the squads named in the token. */
  public static final String SQUAD = "ControlPlane.Squad";

  /** May retrieve the evidence of runs belonging to the squads named in the token. */
  public static final String EVIDENCE = "ControlPlane.Evidence";

  /**
   * May read the audit trails: administrative changes, re-identification requests, suppressions and
   * evidence retrievals. Held by nobody who can make those changes.
   */
  public static final String AUDITOR = "ControlPlane.Auditor";

  private Roles() {}
}
