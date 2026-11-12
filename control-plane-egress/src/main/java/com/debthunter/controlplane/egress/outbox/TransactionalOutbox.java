package com.debthunter.controlplane.egress.outbox;

import com.debthunter.controlplane.egress.alert.OperationsAlert;
import com.debthunter.controlplane.egress.alert.OperationsAlertPublisher;
import com.debthunter.controlplane.egress.alert.OperationsAlertType;
import com.debthunter.controlplane.store.queue.EnqueueResult;
import com.debthunter.controlplane.store.queue.QueuePolicy;
import com.debthunter.controlplane.store.queue.QueuedItem;
import com.debthunter.controlplane.store.queue.RetryOutcome;
import com.debthunter.controlplane.store.queue.WorkQueue;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * At-least-once delivery of messages to something outside the control plane.
 *
 * <p>A message is durable the moment {@link #submit} returns, and nothing that submits waits for
 * delivery, so a destination that is down only makes messages queue. Delivery then follows these
 * rules:
 *
 * <ul>
 *   <li>The destination is unavailable ({@link DeliveryUnavailableException}): the message is held
 *       and no attempt is counted, so an outage of any length never sets a message aside.
 *   <li>The delivery fails for another reason: it is retried with exponential backoff, and once it
 *       has used the queue's maximum attempts it is set aside.
 *   <li>The message can never be delivered ({@link UndeliverableException}): it is set aside at
 *       once.
 *   <li>Whenever a message is set aside an alert is raised. A message is never discarded silently.
 * </ul>
 *
 * <p>An idempotency key given at {@link #submit} stops a second copy of a message that is still
 * waiting or in progress from being queued, so resubmitting after a crash does not double up. A
 * message handed out twice (a worker died holding it) is the handler's to deal with, which is why
 * delivery must be idempotent.
 *
 * <p>This is a "transactional" outbox in the sense that matters: the queue is part of the system of
 * record, so a message exists if and only if it was durably accepted, and a failed submit is
 * reported to the caller rather than swallowed.
 */
public final class TransactionalOutbox {

  private final WorkQueue queue;
  private final String kind;
  private final String workerId;
  private final int batchSize;
  private final OperationsAlertPublisher alerts;
  private final Clock clock;

  /**
   * Creates the outbox for one kind of message.
   *
   * @param queue the durable queue; must be configured with {@code kind}
   * @param kind the kind of message
   * @param workerId who drains it
   * @param batchSize the most messages taken at a time
   * @param alerts where alerts for set-aside messages go
   * @param clock the source of "now"
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public TransactionalOutbox(
      WorkQueue queue,
      String kind,
      String workerId,
      int batchSize,
      OperationsAlertPublisher alerts,
      Clock clock) {
    this.queue = Objects.requireNonNull(queue, "queue");
    this.kind = Objects.requireNonNull(kind, "kind");
    this.workerId = Objects.requireNonNull(workerId, "workerId");
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be at least 1");
    }
    this.batchSize = batchSize;
    this.alerts = Objects.requireNonNull(alerts, "alerts");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * The queue policy an outbox kind should be configured with: messages are ordered per key, so one
   * finding's messages are delivered in the order they were submitted.
   *
   * @param maxAttempts how many failed attempts before a message is set aside
   * @param lease how long a worker may hold a message before it is presumed lost
   * @param initialBackoff the wait after the first failure
   * @param maxBackoff the longest wait between attempts, also the wait while a destination is down
   * @return the policy
   */
  public static QueuePolicy orderedPolicy(
      int maxAttempts, Duration lease, Duration initialBackoff, Duration maxBackoff) {
    return new QueuePolicy(true, maxAttempts, lease, initialBackoff, maxBackoff);
  }

  /**
   * Accepts a message for delivery.
   *
   * @param key the ordering key; messages with the same key are delivered one at a time, in order
   * @param idempotencyKey if not null, a message with this key that is still waiting or in progress
   *     prevents a second being queued
   * @param payload what to deliver
   * @return the message's id, and whether it was a duplicate
   * @throws RuntimeException if the message could not be made durable; the caller must not carry on
   *     as if it had been accepted
   */
  public EnqueueResult submit(String key, String idempotencyKey, byte[] payload) {
    Objects.requireNonNull(key, "key");
    Objects.requireNonNull(payload, "payload");
    return queue.enqueue(kind, key, idempotencyKey, payload, null);
  }

  /**
   * Takes one batch of messages and delivers each.
   *
   * @param handler delivers a message
   * @return what happened
   */
  public DrainResult drain(OutboxHandler handler) {
    Objects.requireNonNull(handler, "handler");
    List<QueuedItem> items = queue.claim(kind, workerId, batchSize);
    int delivered = 0;
    int held = 0;
    int retried = 0;
    int deadLettered = 0;
    for (QueuedItem item : items) {
      OutboxMessage message =
          new OutboxMessage(item.id(), item.key(), item.payload(), item.attempts());
      try {
        handler.deliver(message);
        queue.complete(item.id(), workerId);
        delivered++;
      } catch (DeliveryUnavailableException e) {
        queue.hold(item.id(), workerId, "held: " + e.getMessage());
        held++;
      } catch (UndeliverableException e) {
        if (queue.deadLetter(item.id(), workerId, e.getMessage())) {
          alert(item, e.getMessage());
        }
        deadLettered++;
      } catch (RuntimeException e) {
        String reason = e.getClass().getSimpleName() + ": " + e.getMessage();
        RetryOutcome outcome = queue.retry(item.id(), workerId, reason);
        if (outcome == RetryOutcome.DEAD_LETTERED) {
          alert(item, "gave up after " + item.attempts() + " attempts: " + reason);
          deadLettered++;
        } else {
          retried++;
        }
      }
    }
    return new DrainResult(delivered, held, retried, deadLettered);
  }

  private void alert(QueuedItem item, String reason) {
    alerts.publish(
        new OperationsAlert(
            OperationsAlertType.MESSAGE_DEAD_LETTERED,
            kind,
            item.key(),
            item.id(),
            reason == null ? "unknown" : reason,
            clock.instant()));
  }
}
