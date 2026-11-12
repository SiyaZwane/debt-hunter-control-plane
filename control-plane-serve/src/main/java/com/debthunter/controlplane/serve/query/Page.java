package com.debthunter.controlplane.serve.query;

import java.util.List;

/**
 * One page of results.
 *
 * @param items the items
 * @param nextCursor where the next page starts, or null if this is the last
 * @param <T> the item type
 */
public record Page<T>(List<T> items, String nextCursor) {

  /** Copies the items. */
  public Page {
    items = List.copyOf(items);
  }
}
