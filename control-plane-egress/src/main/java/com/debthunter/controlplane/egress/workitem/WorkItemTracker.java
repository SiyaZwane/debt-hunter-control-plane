package com.debthunter.controlplane.egress.workitem;

import com.debthunter.controlplane.store.registry.WorkItemRoute;
import java.util.Optional;

/**
 * The few things the control plane does to a work-item tracker. It is narrow on purpose: it can
 * find an item by its key, create one, and move one between three states. It cannot delete an item,
 * assign it, or edit what people wrote.
 *
 * <p>Every method is safe to repeat. Failures are sorted for the caller: {@link
 * TrackerUnavailableException} when the tracker could not take the call now (down, rate-limiting,
 * refusing our credentials), {@link TrackerRejectedException} when it answered and refused.
 */
public interface WorkItemTracker {

  /**
   * Finds the item carrying a key.
   *
   * @param route where the repository's items live
   * @param key the item's key
   * @return the item, or empty if there is none
   */
  Optional<TrackedItem> find(WorkItemRoute route, String key);

  /**
   * Creates an item.
   *
   * @param route where it goes
   * @param content what it says; carries the key to record on it
   * @return the new item, open
   */
  TrackedItem create(WorkItemRoute route, WorkItemContent content);

  /**
   * Moves an item to a state and records why. Does nothing to the state if the item is already
   * there.
   *
   * @param route where the item lives
   * @param itemId the tracker's identifier
   * @param to the state to put it in
   * @param comment why, as plain text; untrusted parts have already been included, so the adapter
   *     must still render it safely
   */
  void move(WorkItemRoute route, String itemId, WorkItemStatus to, String comment);
}
