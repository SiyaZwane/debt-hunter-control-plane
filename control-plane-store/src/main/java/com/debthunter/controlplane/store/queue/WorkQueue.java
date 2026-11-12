package com.debthunter.controlplane.store.queue;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * A durable queue of work, kept in the system of record.
 *
 * <p>An item is durable the moment {@code enqueue} returns. A worker claims items under a lease and
 * must finish each with {@link #complete}, {@link #retry} or {@link #deadLetter}; if it dies, the
 * lease expires and the item is handed out again, which makes delivery at-least-once. A kind that
 * is <em>ordered</em> never hands out an item while an earlier item with the same key is waiting or
 * in progress, so one repository's work is done in order while other repositories carry on.
 *
 * <p>Finishing an item erases its payload. What was queued is not kept once it has been dealt with.
 *
 * <p>Failure contract: every method may throw if the store is unreachable. A caller that cannot
 * enqueue must say so to its own caller rather than carry on as if it had.
 */
public interface WorkQueue {

  /**
   * Adds an item.
   *
   * @param kind the kind of work; must be one the queue was configured with
   * @param key the ordering key
   * @param dedupKey if not null, an item with the same kind and dedup key that is still waiting or
   *     in progress prevents a second being added
   * @param payload what to work on
   * @param notBefore the earliest time it may be tried, or null for now
   * @return its id, and whether it was a duplicate
   */
  EnqueueResult enqueue(
      String kind, String key, String dedupKey, byte[] payload, Instant notBefore);

  /**
   * Takes items to work on.
   *
   * @param kind the kind of work
   * @param worker who is taking them
   * @param max the most to take
   * @return the items, oldest first. Each has been tried one more time than before
   */
  List<QueuedItem> claim(String kind, String worker, int max);

  /**
   * Marks an item done and erases its payload.
   *
   * @param id the item
   * @param worker who holds it
   * @return false if the worker no longer holds it
   */
  boolean complete(long id, String worker);

  /**
   * Records a failed attempt: schedules another after a backoff, or sets the item aside as dead if
   * it has used its attempts.
   *
   * @param id the item
   * @param worker who holds it
   * @param error why it failed; kept for the operator, so it must not contain the payload
   * @return what happened
   */
  RetryOutcome retry(long id, String worker, String error);

  /**
   * Puts an item back to be tried again later <em>without</em> counting the attempt, for a failure
   * that is not the item's fault (a key store or database that is down). Such an item is held for
   * as long as it takes and is never set aside as dead on account of it. The wait is the kind's
   * maximum backoff.
   *
   * @param id the item
   * @param worker who holds it
   * @param reason why it is held; kept for the operator, so it must not contain the payload
   * @return false if the worker no longer holds it
   */
  boolean hold(long id, String worker, String reason);

  /**
   * Sets an item aside as dead without further attempts, for work that can never succeed.
   *
   * @param id the item
   * @param worker who holds it
   * @param error why
   * @return false if the worker no longer holds it
   */
  boolean deadLetter(long id, String worker, String error);

  /**
   * Deletes finished and dead items that are old enough, so neither the table nor any payload kept
   * for investigation lasts forever.
   *
   * @param doneBefore delete done items completed before this
   * @param deadBefore delete dead items set aside before this
   * @return how many were deleted
   */
  int purge(Instant doneBefore, Instant deadBefore);

  /** An item as an operator sees it. */
  Optional<QueueEntry> find(long id);

  /** Counts by state for one kind. */
  QueueCounts counts(String kind);
}
