package com.debthunter.controlplane.egress.digest;

import com.debthunter.controlplane.egress.outbox.TransactionalOutbox;
import com.debthunter.controlplane.store.lifecycle.TransitionLogRepository;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistry;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Makes one digest per squad per period, never one per finding.
 *
 * <p>Periods are fixed lengths from the Unix epoch. The first time a tick falls in a new period the
 * service closes the one before it: it fixes which slice of the transition log that period covers,
 * builds each squad's digest from that slice, records it, and hands it to the outbox. Every step is
 * idempotent, so a tick can be repeated, run on two instances, or interrupted at any point, and
 * each squad still gets exactly one digest for the period (a crash between delivering and noting it
 * can send the same digest twice, as any at-least-once delivery can).
 *
 * <p>A squad is a repository owner. A digest is built only from the repositories that owner owns.
 * The first period ever seen is a baseline: it is closed without a digest, so a squad's first
 * digest covers changes since the service started, never all history. A period in which nothing
 * changed produces no digest unless configured to.
 */
public final class DigestNotificationService {

  private static final Logger LOG = LoggerFactory.getLogger(DigestNotificationService.class);
  private static final ObjectMapper JSON = new ObjectMapper();

  private final RepositoryRegistry registry;
  private final TransitionLogRepository log;
  private final DigestLedger ledger;
  private final DigestBuilder builder;
  private final TransactionalOutbox outbox;
  private final DigestSettings settings;
  private final Clock clock;
  private Instant lastClosed;

  /**
   * Creates the service.
   *
   * @param registry which repositories each owner has
   * @param log the transition log
   * @param ledger remembers windows and built digests
   * @param builder builds a digest
   * @param outbox delivers digests
   * @param settings the cadence and whether to send empty digests
   * @param clock the source of "now"
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public DigestNotificationService(
      RepositoryRegistry registry,
      TransitionLogRepository log,
      DigestLedger ledger,
      DigestBuilder builder,
      TransactionalOutbox outbox,
      DigestSettings settings,
      Clock clock) {
    this.registry = Objects.requireNonNull(registry, "registry");
    this.log = Objects.requireNonNull(log, "log");
    this.ledger = Objects.requireNonNull(ledger, "ledger");
    this.builder = Objects.requireNonNull(builder, "builder");
    this.outbox = Objects.requireNonNull(outbox, "outbox");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  /**
   * Does whatever is due: closes the last completed period if that has not been done, and hands any
   * digests built but not yet queued to the outbox. Cheap when nothing is due, and safe to call as
   * often as wanted.
   *
   * @return how many digests were handed to the outbox
   */
  public synchronized int tick() {
    Instant now = clock.instant();
    long cadence = settings.cadence().toSeconds();
    Instant current = Instant.ofEpochSecond(Math.floorDiv(now.getEpochSecond(), cadence) * cadence);
    Instant previous = current.minus(settings.cadence());

    if (!previous.equals(lastClosed)) {
      close(previous, current);
    }
    int queued = 0;
    for (DigestLedger.Entry entry : ledger.unqueued()) {
      DigestJob job = new DigestJob(entry.owner(), entry.periodStart().toString());
      outbox.submit(entry.owner(), job.idempotencyKey(), job.encode());
      ledger.markQueued(entry.owner(), entry.periodStart(), clock.instant());
      queued++;
    }
    lastClosed = previous;
    return queued;
  }

  private void close(Instant start, Instant end) {
    DigestLedger.Window window = ledger.window(start, end, log.latestSeq());
    Map<String, Set<String>> byOwner = new TreeMap<>();
    for (OnboardedRepository r : registry.all()) {
      byOwner.computeIfAbsent(r.owner(), o -> new TreeSet<>()).add(r.repository());
    }
    for (Map.Entry<String, Set<String>> squad : byOwner.entrySet()) {
      if (ledger.find(squad.getKey(), start).isPresent()) {
        continue;
      }
      DigestMessage message =
          builder.build(
              squad.getKey(), squad.getValue(), start, end, window.fromSeq(), window.toSeq());
      boolean skip = window.baseline() || (message.isEmpty() && !settings.sendEmpty());
      ledger.record(squad.getKey(), start, skip ? null : write(message), clock.instant());
    }
    LOG.debug("closed digest period {}", start);
  }

  /**
   * Builds a digest for a squad over a set of repositories, refusing any the squad does not own.
   * For callers that name repositories (for example a request on a squad's behalf); the scheduled
   * path takes them from the registry and cannot be asked for another squad's.
   *
   * @param squad the squad
   * @param repositories the repositories wanted
   * @param start the period's start
   * @param end the period's end
   * @param fromSeq the last log position before the period
   * @param toSeq the last log position in it
   * @return the digest
   * @throws CrossSquadException if any repository belongs to another squad or is unknown
   */
  public DigestMessage digestFor(
      String squad,
      Set<String> repositories,
      Instant start,
      Instant end,
      long fromSeq,
      long toSeq) {
    for (String repository : repositories) {
      boolean owned = registry.find(repository).map(r -> r.owner().equals(squad)).orElse(false);
      if (!owned) {
        throw new CrossSquadException("The squad does not own " + repository);
      }
    }
    return builder.build(squad, repositories, start, end, fromSeq, toSeq);
  }

  /** The digest as JSON, as it is stored. */
  static String write(DigestMessage message) {
    try {
      return JSON.writeValueAsString(message);
    } catch (IOException e) {
      throw new IllegalStateException("A digest could not be written", e);
    }
  }

  /** Reads a digest back from its stored JSON. */
  static DigestMessage read(String json) {
    try {
      return JSON.readValue(json, DigestMessage.class);
    } catch (IOException e) {
      throw new IllegalArgumentException("Not a digest", e);
    }
  }

  /** The cadence in use. */
  public Duration cadence() {
    return settings.cadence();
  }
}
