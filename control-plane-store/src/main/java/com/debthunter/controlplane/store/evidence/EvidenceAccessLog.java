package com.debthunter.controlplane.store.evidence;

import java.time.Instant;
import java.util.List;

/**
 * The append-only record of who asked for which run's evidence, and why.
 *
 * <p>There is no update and no delete. {@link #record} throws if the entry could not be made
 * durable, and a caller that needs the record must not release evidence it could not record.
 */
public interface EvidenceAccessLog {

  /**
   * Appends an entry.
   *
   * @param access the entry
   * @throws RuntimeException if it could not be recorded
   */
  void record(EvidenceAccess access);

  /**
   * Reads entries from a time on, oldest first.
   *
   * @param from the earliest time wanted
   * @return the entries
   */
  List<EvidenceAccess> since(Instant from);

  /**
   * Reads at most a number of entries from a time on, oldest first.
   *
   * @param from the earliest time wanted
   * @param limit the most entries to return
   * @return the entries
   */
  List<EvidenceAccess> since(Instant from, int limit);
}
