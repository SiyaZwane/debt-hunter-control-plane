package com.debthunter.controlplane.store.queue;

/**
 * What enqueueing did.
 *
 * @param id the item's id; for a duplicate, the id of the item already waiting
 * @param duplicate whether an item with the same dedup key was already queued or in progress, in
 *     which case nothing was added
 */
public record EnqueueResult(long id, boolean duplicate) {}
