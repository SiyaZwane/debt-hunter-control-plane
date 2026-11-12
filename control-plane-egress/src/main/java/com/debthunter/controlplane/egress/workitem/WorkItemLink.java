package com.debthunter.controlplane.egress.workitem;

import java.time.Instant;
import java.util.Objects;

/**
 * The record that a finding has a tracker item. There is at most one per finding, in whatever state
 * the item is, which is how "one item per fingerprint" is kept.
 *
 * @param repository the repository
 * @param projectSlice the project slice
 * @param fingerprint the finding's fingerprint
 * @param itemKey the key recorded on the tracker item
 * @param tracker which tracker holds it
 * @param itemId the tracker's identifier, or {@code null} while the item has been reserved but not
 *     yet recorded as created
 * @param itemUrl where to open it, or {@code null}
 * @param status the state the control plane last put it in, or {@code null} if not yet known
 * @param updatedAt when this record last changed
 */
public record WorkItemLink(
    String repository,
    String projectSlice,
    String fingerprint,
    String itemKey,
    String tracker,
    String itemId,
    String itemUrl,
    WorkItemStatus status,
    Instant updatedAt) {

  /** Validates the components. */
  public WorkItemLink {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(projectSlice, "projectSlice");
    Objects.requireNonNull(fingerprint, "fingerprint");
    Objects.requireNonNull(itemKey, "itemKey");
    Objects.requireNonNull(tracker, "tracker");
    Objects.requireNonNull(updatedAt, "updatedAt");
  }

  /** Whether the tracker item is known to exist. */
  public boolean hasItem() {
    return itemId != null;
  }

  /** A copy recording the item. */
  public WorkItemLink withItem(TrackedItem item, WorkItemStatus now, Instant at) {
    return new WorkItemLink(
        repository, projectSlice, fingerprint, itemKey, tracker, item.id(), item.url(), now, at);
  }

  /** A copy recording a new status. */
  public WorkItemLink withStatus(WorkItemStatus now, Instant at) {
    return new WorkItemLink(
        repository, projectSlice, fingerprint, itemKey, tracker, itemId, itemUrl, now, at);
  }
}
