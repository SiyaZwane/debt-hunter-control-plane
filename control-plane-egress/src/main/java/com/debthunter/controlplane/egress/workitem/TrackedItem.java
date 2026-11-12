package com.debthunter.controlplane.egress.workitem;

import java.util.Objects;

/**
 * An item as the tracker holds it.
 *
 * @param id the tracker's identifier for the item (for example {@code DH-12} or {@code 4711})
 * @param url where a person can open it
 * @param status where it stands, or {@code null} if the tracker's status is none of the three the
 *     control plane sets
 */
public record TrackedItem(String id, String url, WorkItemStatus status) {

  /** Validates the components. */
  public TrackedItem {
    Objects.requireNonNull(id, "id");
    Objects.requireNonNull(url, "url");
  }
}
