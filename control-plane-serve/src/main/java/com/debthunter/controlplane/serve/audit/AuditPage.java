package com.debthunter.controlplane.serve.audit;

import java.time.Instant;
import java.util.List;

/**
 * A page of one audit trail.
 *
 * @param trail which trail
 * @param items the entries, oldest first
 * @param nextFrom where to ask for the next page (pass it as {@code from}), or null when this page
 *     reached the end. Entries sharing that instant are repeated, so a reader de-duplicates on the
 *     whole entry.
 * @param generatedAt when this was produced
 */
public record AuditPage(
    AuditReadService.Trail trail, List<AuditLine> items, Instant nextFrom, Instant generatedAt) {

  /** Copies the items. */
  public AuditPage {
    items = List.copyOf(items);
  }

  /**
   * One audit entry as an auditor sees it.
   *
   * @param at when
   * @param caller who
   * @param action what they did
   * @param target what it was done to
   * @param detail a stated reason or purpose, if any
   * @param outcome whether it was allowed, denied or (for evidence) not found
   */
  public record AuditLine(
      Instant at, String caller, String action, String target, String detail, String outcome) {}
}
