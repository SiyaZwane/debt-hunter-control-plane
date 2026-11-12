package com.debthunter.controlplane.store.queue;

import java.time.Instant;
import java.util.Arrays;

/**
 * An item handed to a worker.
 *
 * @param id the item's id
 * @param kind the kind of work
 * @param key the ordering key (for intake, the repository)
 * @param payload what to work on
 * @param attempts how many times it has now been tried, including this one
 * @param enqueuedAt when it was queued
 */
public record QueuedItem(
    long id, String kind, String key, byte[] payload, int attempts, Instant enqueuedAt) {

  /** Copies the payload. */
  public QueuedItem {
    payload = Arrays.copyOf(payload, payload.length);
  }

  /** A copy of the payload. */
  @Override
  public byte[] payload() {
    return Arrays.copyOf(payload, payload.length);
  }
}
