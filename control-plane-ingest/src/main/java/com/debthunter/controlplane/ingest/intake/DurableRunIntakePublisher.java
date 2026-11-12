package com.debthunter.controlplane.ingest.intake;

import com.debthunter.controlplane.store.queue.PayloadCipher;
import com.debthunter.controlplane.store.queue.WorkQueue;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Puts a bundle on the durable intake queue. The bundle is encrypted first: it still holds the
 * contributors' real identities, so it is never readable in the database. It is durable when this
 * returns, because the enqueue is a committed database write.
 *
 * <p>This blocks the calling thread for the duration of the database write, which is the contract
 * of {@link RunIntakeQueuePublisher}: callers on a non-blocking thread must offload it, and bound
 * the wait. Any failure, including an unreachable database, is reported as {@link
 * QueueUnavailableException}.
 */
public final class DurableRunIntakePublisher implements RunIntakeQueuePublisher {

  private final WorkQueue queue;
  private final PayloadCipher cipher;
  private final IntakeEnvelopeCodec codec;

  /**
   * Creates the publisher.
   *
   * @param queue the durable queue
   * @param cipher encrypts the bundle before it is stored
   * @param codec encodes envelopes
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public DurableRunIntakePublisher(
      WorkQueue queue, PayloadCipher cipher, IntakeEnvelopeCodec codec) {
    this.queue = Objects.requireNonNull(queue, "queue");
    this.cipher = Objects.requireNonNull(cipher, "cipher");
    this.codec = Objects.requireNonNull(codec, "codec");
  }

  @Override
  public CompletionStage<Void> publish(IntakeEnvelope envelope) {
    try {
      byte[] sealed =
          cipher.seal(
              codec.encode(envelope).getBytes(StandardCharsets.UTF_8),
              IntakeQueue.context(envelope.repository()));
      queue.enqueue(IntakeQueue.KIND, envelope.repository(), null, sealed, null);
      return CompletableFuture.completedFuture(null);
    } catch (RuntimeException e) {
      return CompletableFuture.failedFuture(
          new QueueUnavailableException("The intake queue could not take the bundle", e));
    }
  }
}
