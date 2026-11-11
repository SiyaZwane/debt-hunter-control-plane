package com.debthunter.controlplane.api.admin;

/** Where administration changes are recorded. */
@FunctionalInterface
public interface AdminAuditLog {

  /**
   * Records a change.
   *
   * @param entry what is about to be done, and by whom
   * @throws RuntimeException if it could not be recorded; the change then does not happen, because
   *     an unrecorded change to who may write is worse than a refused one
   */
  void record(AdminAuditEntry entry);
}
