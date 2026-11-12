package com.debthunter.controlplane.store.queue;

/** What recording a failed attempt did. */
public enum RetryOutcome {
  /** It will be tried again after a delay. */
  RETRY_SCHEDULED,
  /** It had used its attempts and was set aside as dead. */
  DEAD_LETTERED,
  /**
   * The worker no longer held the item (its lease expired and it went elsewhere); nothing changed.
   */
  LEASE_LOST
}
