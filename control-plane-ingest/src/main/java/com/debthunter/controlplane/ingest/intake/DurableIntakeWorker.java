package com.debthunter.controlplane.ingest.intake;

import com.debthunter.controlplane.ingest.pseudonymise.KeyStoreUnavailableException;
import com.debthunter.controlplane.ingest.receive.RunBundleReceiver;
import com.debthunter.controlplane.store.jdbc.StoreException;
import com.debthunter.controlplane.store.queue.PayloadCipher;
import com.debthunter.controlplane.store.queue.QueuedItem;
import com.debthunter.controlplane.store.queue.RetryOutcome;
import com.debthunter.controlplane.store.queue.WorkQueue;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Takes bundles off the durable intake queue and feeds each to a {@link RunBundleReceiver}.
 *
 * <p>Delivery is at-least-once: a bundle is finished only after the receiver has dealt with it, and
 * the receiver's de-duplication makes a redelivery harmless. If the receiver fails, the bundle is
 * retried after a growing delay, and its repository's later bundles wait behind it; after the
 * queue's maximum attempts it is set aside as dead and logged at error level, so one bundle that
 * can never succeed cannot hold up its repository for ever. A bundle that cannot even be decrypted
 * or decoded can never succeed, so it is set aside at once, and so is one the pipeline quarantines:
 * both keep their encrypted payload for investigation, where a finished bundle's payload is erased.
 *
 * <p>A dependency being down is not the bundle's fault. If the pseudonym key store or the database
 * is unavailable the bundle is <em>held</em>, without counting the attempt, for as long as that
 * lasts and is never set aside on account of it (CP-BC-06: ingest must hold, not lose).
 *
 * <p>Only the exception's type is recorded against a failed item, never its message, because a
 * message can quote the bundle.
 */
public final class DurableIntakeWorker implements AutoCloseable {

  private static final Logger log = LoggerFactory.getLogger(DurableIntakeWorker.class);
  private static final Duration MAX_ERROR_BACKOFF = Duration.ofSeconds(30);

  private final WorkQueue queue;
  private final PayloadCipher cipher;
  private final IntakeEnvelopeCodec codec;
  private final RunBundleReceiver receiver;
  private final String workerId;
  private final int batchSize;
  private final Duration pollInterval;
  private final CountDownLatch stopped = new CountDownLatch(1);
  private Thread thread;

  /**
   * Creates the worker.
   *
   * @param queue the durable queue
   * @param cipher decrypts the bundles
   * @param codec decodes envelopes
   * @param receiver where decoded bundles go
   * @param workerId who this worker is, recorded against the bundles it holds
   * @param batchSize the most bundles taken at a time
   * @param pollInterval how long to wait when the queue is empty
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public DurableIntakeWorker(
      WorkQueue queue,
      PayloadCipher cipher,
      IntakeEnvelopeCodec codec,
      RunBundleReceiver receiver,
      String workerId,
      int batchSize,
      Duration pollInterval) {
    this.queue = Objects.requireNonNull(queue, "queue");
    this.cipher = Objects.requireNonNull(cipher, "cipher");
    this.codec = Objects.requireNonNull(codec, "codec");
    this.receiver = Objects.requireNonNull(receiver, "receiver");
    this.workerId = Objects.requireNonNull(workerId, "workerId");
    if (batchSize < 1) {
      throw new IllegalArgumentException("batchSize must be at least 1");
    }
    this.batchSize = batchSize;
    this.pollInterval = Objects.requireNonNull(pollInterval, "pollInterval");
  }

  /**
   * Takes one batch and deals with it.
   *
   * @return how many bundles were taken; zero means the queue had nothing ready
   */
  public int runOnce() {
    List<QueuedItem> items = queue.claim(IntakeQueue.KIND, workerId, batchSize);
    for (QueuedItem item : items) {
      process(item);
    }
    return items.size();
  }

  private void process(QueuedItem item) {
    IntakeEnvelope envelope;
    try {
      byte[] plain = cipher.open(item.payload(), IntakeQueue.context(item.key()));
      envelope = codec.decode(new String(plain, StandardCharsets.UTF_8));
    } catch (RuntimeException e) {
      log.error(
          "setting aside an unreadable intake item id={} repository={}", item.id(), item.key(), e);
      queue.deadLetter(item.id(), workerId, "unreadable: " + e.getClass().getSimpleName());
      return;
    }
    RunBundleReceiver.Outcome outcome;
    try {
      outcome = receiver.receive(envelope);
    } catch (RuntimeException e) {
      if (isInfrastructureFailure(e)) {
        // The bundle is not at fault: a key store or the database is down. Hold it, however long
        // that takes, and never set it aside on account of it: ingest must hold, not lose.
        queue.hold(item.id(), workerId, "held: " + rootType(e));
        log.warn(
            "holding intake bundle while a dependency is unavailable runId={} repository={}: {}",
            envelope.runId(),
            item.key(),
            rootType(e));
        return;
      }
      RetryOutcome retried =
          queue.retry(item.id(), workerId, "failed: " + e.getClass().getSimpleName());
      if (retried == RetryOutcome.DEAD_LETTERED) {
        log.error(
            "intake bundle set aside after {} attempts runId={} repository={}",
            item.attempts(),
            envelope.runId(),
            item.key(),
            e);
      } else {
        log.warn(
            "intake bundle failed attempt {}; will retry runId={} repository={}",
            item.attempts(),
            envelope.runId(),
            item.key(),
            e);
      }
      return;
    }
    if (outcome == RunBundleReceiver.Outcome.SET_ASIDE) {
      // The pipeline could never process it and has quarantined its metadata. Keep the bundle
      // itself, still encrypted, for the investigation window rather than erasing it as finished.
      queue.deadLetter(item.id(), workerId, "set aside by the pipeline; see the quarantine record");
      return;
    }
    if (!queue.complete(item.id(), workerId)) {
      log.warn(
          "finished intake item {} after losing its lease; it will be delivered again and"
              + " de-duplicated",
          item.id());
    }
  }

  /**
   * Whether a failure is a dependency being unavailable rather than something wrong with the
   * bundle: the pseudonym key store, or the database.
   */
  static boolean isInfrastructureFailure(Throwable failure) {
    for (Throwable t = failure; t != null; t = t.getCause()) {
      if (t instanceof KeyStoreUnavailableException || t instanceof StoreException) {
        return true;
      }
      if (t.getCause() == t) {
        break;
      }
    }
    return false;
  }

  private static String rootType(Throwable failure) {
    Throwable t = failure;
    while (t.getCause() != null && t.getCause() != t) {
      t = t.getCause();
    }
    return t.getClass().getSimpleName();
  }

  /** Starts working on a background thread. Idempotent. */
  public synchronized void start() {
    if (thread != null) {
      return;
    }
    thread = new Thread(this::run, "intake-worker");
    thread.setDaemon(true);
    thread.start();
  }

  private void run() {
    Duration errorBackoff = Duration.ofMillis(200);
    while (stopped.getCount() > 0) {
      try {
        int taken = runOnce();
        errorBackoff = Duration.ofMillis(200);
        if (taken == 0 && pause(pollInterval)) {
          return;
        }
      } catch (RuntimeException e) {
        log.warn("the intake queue could not be read; will retry in {}", errorBackoff, e);
        if (pause(errorBackoff)) {
          return;
        }
        errorBackoff =
            errorBackoff.multipliedBy(2).compareTo(MAX_ERROR_BACKOFF) > 0
                ? MAX_ERROR_BACKOFF
                : errorBackoff.multipliedBy(2);
      }
    }
  }

  /** Waits, returning true if the worker was told to stop meanwhile. */
  private boolean pause(Duration duration) {
    try {
      return stopped.await(duration.toMillis(), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return true;
    }
  }

  /** Stops working and waits for the current item to finish. Idempotent. */
  @Override
  public void close() {
    Thread working;
    synchronized (this) {
      stopped.countDown();
      working = thread;
      thread = null;
    }
    if (working != null) {
      try {
        working.join(10_000);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
