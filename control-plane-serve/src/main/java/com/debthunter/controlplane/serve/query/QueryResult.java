package com.debthunter.controlplane.serve.query;

import java.time.Instant;
import java.util.List;

/**
 * A page of findings with how fresh it is.
 *
 * @param items the findings
 * @param nextCursor where the next page starts, or null if this is the last
 * @param asOf when the newest run these findings reflect was received, or null if there is none
 * @param generatedAt when this answer was produced
 */
public record QueryResult(
    List<FindingRow> items, String nextCursor, Instant asOf, Instant generatedAt) {

  /** Copies the items. */
  public QueryResult {
    items = List.copyOf(items);
  }
}
