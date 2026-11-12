package com.debthunter.controlplane.store.queue;

import java.time.Instant;

/**
 * A queue item as an operator sees it. It never includes the payload, only how big it is, so
 * looking at the queue cannot expose what was queued.
 *
 * @param id the item's id
 * @param kind the kind of work
 * @param key the ordering key
 * @param status where it is
 * @param attempts how many times it has been tried
 * @param lastError why the last attempt failed, or {@code null}
 * @param notBefore the earliest time it may next be tried
 * @param payloadBytes how large the stored payload is; zero once it has been erased
 */
public record QueueEntry(
    long id,
    String kind,
    String key,
    Status status,
    int attempts,
    String lastError,
    Instant notBefore,
    int payloadBytes) {

  /** Where an item is. */
  public enum Status {
    /** Waiting to be tried, now or after a delay. */
    PENDING,
    /** Held by a worker. */
    IN_PROGRESS,
    /** Finished; the payload has been erased. */
    DONE,
    /** Given up on after too many attempts, or found to be unprocessable. */
    DEAD
  }
}
