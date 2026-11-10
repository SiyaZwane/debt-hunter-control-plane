package com.debthunter.controlplane.api.ingestion;

/**
 * Whether a workload identity may publish for a repository.
 *
 * @param allowed {@code true} only if the identity is scoped to the repository
 * @param reason why it was refused, or {@code null} if allowed
 */
public record ScopeDecision(boolean allowed, String reason) {

  /**
   * A grant.
   *
   * @return an allowing decision
   */
  public static ScopeDecision allow() {
    return new ScopeDecision(true, null);
  }

  /**
   * A refusal.
   *
   * @param reason why the request was refused
   * @return a denying decision
   */
  public static ScopeDecision deny(String reason) {
    return new ScopeDecision(false, reason);
  }
}
