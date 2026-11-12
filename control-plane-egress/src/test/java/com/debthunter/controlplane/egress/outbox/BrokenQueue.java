package com.debthunter.controlplane.egress.outbox;

import com.debthunter.controlplane.store.queue.EnqueueResult;
import com.debthunter.controlplane.store.queue.QueueCounts;
import com.debthunter.controlplane.store.queue.QueueEntry;
import com.debthunter.controlplane.store.queue.QueuedItem;
import com.debthunter.controlplane.store.queue.RetryOutcome;
import com.debthunter.controlplane.store.queue.WorkQueue;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** A queue whose store is down: every call fails. */
public final class BrokenQueue implements WorkQueue {

  private static IllegalStateException down() {
    return new IllegalStateException("the store is down");
  }

  @Override
  public EnqueueResult enqueue(
      String kind, String key, String dedupKey, byte[] payload, Instant notBefore) {
    throw down();
  }

  @Override
  public List<QueuedItem> claim(String kind, String worker, int max) {
    throw down();
  }

  @Override
  public boolean complete(long id, String worker) {
    throw down();
  }

  @Override
  public RetryOutcome retry(long id, String worker, String error) {
    throw down();
  }

  @Override
  public boolean hold(long id, String worker, String reason) {
    throw down();
  }

  @Override
  public boolean defer(long id, String worker, Instant notBefore) {
    throw down();
  }

  @Override
  public int countStarted(String kind, String key, Instant finishedSince, long excludeId) {
    throw down();
  }

  @Override
  public boolean deadLetter(long id, String worker, String error) {
    throw down();
  }

  @Override
  public int purge(Instant doneBefore, Instant deadBefore) {
    throw down();
  }

  @Override
  public Optional<QueueEntry> find(long id) {
    throw down();
  }

  @Override
  public QueueCounts counts(String kind) {
    throw down();
  }
}
