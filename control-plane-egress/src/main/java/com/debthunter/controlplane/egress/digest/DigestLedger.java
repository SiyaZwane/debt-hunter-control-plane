package com.debthunter.controlplane.egress.digest;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Remembers which slice of the transition log each period covers, and the digest built for each
 * squad and period, so a digest is built once and sent once however often it is asked for.
 */
public interface DigestLedger {

  /**
   * A period and the slice of the log it covers.
   *
   * @param start the period's start
   * @param end the period's end
   * @param fromSeq the last log position before the period
   * @param toSeq the last log position in it
   * @param baseline whether this was the first period ever seen, which only sets a starting point
   */
  record Window(Instant start, Instant end, long fromSeq, long toSeq, boolean baseline) {}

  /**
   * A squad's digest for a period.
   *
   * @param owner the squad
   * @param periodStart the period's start
   * @param payload the digest as built (JSON), or {@code null} if there was nothing to say
   * @param queuedAt when it was handed to the outbox, or {@code null}
   * @param sentAt when it was delivered, or {@code null}
   */
  record Entry(
      String owner, Instant periodStart, String payload, Instant queuedAt, Instant sentAt) {}

  /**
   * Fixes the window a period covers, the first time it is asked for. It runs from where the
   * previous recorded period stopped. With no previous period it is empty (a baseline), so a first
   * digest never replays history.
   *
   * @param start the period's start
   * @param end the period's end
   * @param latestSeq the newest log position now
   * @return the window, new or as fixed before
   */
  Window window(Instant start, Instant end, long latestSeq);

  /**
   * Records a squad's digest for a period, if there is none yet.
   *
   * @param owner the squad
   * @param periodStart the period's start
   * @param payload the digest as built, or {@code null} if there was nothing to say; a null payload
   *     is recorded as already queued and sent
   * @param now the current time
   * @return whether it was recorded (false if one was already there)
   */
  boolean record(String owner, Instant periodStart, String payload, Instant now);

  /** Looks a squad's digest for a period up. */
  Optional<Entry> find(String owner, Instant periodStart);

  /** Digests recorded but not yet handed to the outbox. */
  List<Entry> unqueued();

  /** Notes that a digest has been handed to the outbox. */
  void markQueued(String owner, Instant periodStart, Instant now);

  /** Notes that a digest has been delivered. */
  void markSent(String owner, Instant periodStart, Instant now);
}
