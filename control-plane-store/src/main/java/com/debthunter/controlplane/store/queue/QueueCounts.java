package com.debthunter.controlplane.store.queue;

/**
 * How many items of a kind are in each state.
 *
 * @param pending waiting
 * @param inProgress held by a worker
 * @param done finished (until purged)
 * @param dead given up on (until purged)
 */
public record QueueCounts(long pending, long inProgress, long done, long dead) {}
