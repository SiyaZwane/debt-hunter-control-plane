package com.debthunter.controlplane.store.audit;

import java.time.Instant;
import java.util.List;

/**
 * The append-only record of administrative, re-identification and suppression activity.
 *
 * <p>There is no update here, and no delete: removing old entries is the separate, floor-protected
 * {@link AuditPurger}. {@link #record} throws if the entry could not be made durable, and a caller
 * that is about to do something that must be recorded must not do it if that happens.
 */
public interface AuditTrail {

  /**
   * Appends an entry.
   *
   * @param entry the entry
   * @throws RuntimeException if it could not be recorded
   */
  void record(AuditEntry entry);

  /**
   * Reads entries of one kind from a time on, oldest first.
   *
   * @param kind which trail
   * @param from the earliest time wanted, inclusive
   * @param limit the most entries to return
   * @return the entries
   */
  List<AuditEntry> read(AuditEntry.Kind kind, Instant from, int limit);
}
