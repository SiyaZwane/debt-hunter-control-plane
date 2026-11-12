package com.debthunter.controlplane.egress.digest;

import com.debthunter.controlplane.egress.MutableClock;
import com.debthunter.controlplane.egress.alert.RecordingAlerts;
import com.debthunter.controlplane.egress.outbox.TransactionalOutbox;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.findings.PseudonymisedFindingStore;
import com.debthunter.controlplane.store.identity.Pseudonym;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.lifecycle.FindingTransitionEvent;
import com.debthunter.controlplane.store.lifecycle.TransitionCause;
import com.debthunter.controlplane.store.lifecycle.TransitionLogRepository;
import com.debthunter.controlplane.store.queue.WorkQueue;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistry;
import com.debthunter.controlplane.store.registry.WorkItemRoute;
import com.debthunter.controlplane.store.rollout.RolloutStage;
import com.debthunter.controlplane.store.suppressions.Suppression;
import com.debthunter.controlplane.store.suppressions.SuppressionRepository;
import com.debthunter.domain.Category;
import com.debthunter.domain.Severity;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Digest egress wired over whichever stores a test supplies, with recording chat channels, a clock
 * the test moves, and helpers to say what happened to findings.
 */
public class DigestHarness {

  public static final String WIDGETS = "acme/widgets";
  public static final String GADGETS = "acme/gadgets";
  public static final String THING = "other/thing";
  public static final String TEAM_A = "team-a";
  public static final String TEAM_B = "team-b";
  public static final String CONTRIBUTOR = "c".repeat(64);

  /** The stores a harness runs over. */
  public record Parts(
      TransitionLogRepository log,
      PseudonymisedFindingStore findings,
      SuppressionRepository suppressions,
      RepositoryRegistry registry,
      DigestLedger ledger,
      SquadChannelRepository channels,
      OpenFindingCounts open,
      WorkQueue queue) {}

  public final MutableClock clock;
  public final Parts parts;
  public final RecordingAlerts alerts = new RecordingAlerts();
  public final RecordingChannel slack = new RecordingChannel();
  public final RecordingChannel teams = new RecordingChannel();
  public final Map<String, RolloutStage> stages = new HashMap<>();
  public final Map<String, URI> secrets = new HashMap<>();
  public final TransactionalOutbox outbox;
  public final DigestBuilder builder;
  public final DigestNotificationService service;
  public final DigestSendHandler handler;
  public final DigestWorker worker;
  private final AtomicInteger events = new AtomicInteger();

  public DigestHarness(Parts parts, MutableClock clock) {
    this(parts, clock, DigestSettings.defaults());
  }

  public DigestHarness(Parts parts, MutableClock clock, DigestSettings settings) {
    this.parts = parts;
    this.clock = clock;
    outbox = new TransactionalOutbox(parts.queue(), DigestQueue.KIND, "w1", 20, alerts, clock);
    builder =
        new DigestBuilder(
            parts.log(),
            parts.findings(),
            parts.suppressions(),
            () -> stages,
            parts.open(),
            squad -> "https://cp.example/squads/" + squad,
            settings,
            clock);
    service =
        new DigestNotificationService(
            parts.registry(), parts.log(), parts.ledger(), builder, outbox, settings, clock);
    handler =
        new DigestSendHandler(
            parts.ledger(),
            parts.channels(),
            ref -> Optional.ofNullable(secrets.get(ref)),
            Map.of(SquadChannel.Kind.SLACK, slack, SquadChannel.Kind.TEAMS, teams),
            clock);
    worker = new DigestWorker(service, outbox, handler, Duration.ofMillis(10));
    register(WIDGETS, TEAM_A);
    register(GADGETS, TEAM_A);
    register(THING, TEAM_B);
    stages.put(WIDGETS, RolloutStage.STAGE_3);
    stages.put(GADGETS, RolloutStage.STAGE_1);
  }

  public static Map<Severity, Integer> none() {
    return new EnumMap<>(Severity.class);
  }

  public void register(String repository, String owner) {
    parts
        .registry()
        .save(
            new OnboardedRepository(
                repository, owner, "tier-1", "v1", "STAGE_3", new WorkItemRoute("jira", "DH")));
  }

  /** Gives a squad a Slack channel whose webhook is {@code https://hooks.example/<owner>}. */
  public URI slackFor(String owner) {
    return channel(owner, SquadChannel.Kind.SLACK);
  }

  public URI teamsFor(String owner) {
    return channel(owner, SquadChannel.Kind.TEAMS);
  }

  private URI channel(String owner, SquadChannel.Kind kind) {
    URI webhook = URI.create("https://hooks.example/" + owner + "/" + kind);
    parts.channels().save(new SquadChannel(owner, kind, "ref-" + owner));
    secrets.put("ref-" + owner, webhook);
    return webhook;
  }

  public static PseudonymisedFinding finding(
      String fingerprint, Severity severity, String path, String message) {
    return new PseudonymisedFinding(
        "f-" + fingerprint,
        "codemaat.hotspot",
        Category.HOTSPOT,
        severity,
        0.8,
        path,
        7,
        message,
        Map.of(),
        0.6,
        true,
        fingerprint,
        List.of(Pseudonym.fromStored(CONTRIBUTOR)));
  }

  /** Records that a run reported findings. */
  public void run(String runId, String repository, PseudonymisedFinding... found) {
    parts.findings().saveRun(runId, repository, List.of(found));
  }

  public void transition(
      String repository,
      String fingerprint,
      FindingState from,
      FindingState to,
      TransitionCause cause,
      String runId) {
    parts
        .log()
        .append(
            List.of(
                new FindingTransitionEvent(
                    "ev-" + events.incrementAndGet(),
                    repository,
                    "root",
                    fingerprint,
                    from,
                    to,
                    runId,
                    clock.instant(),
                    cause)));
  }

  /** A finding seen for the first time, reported by a run that is stored with it. */
  public void newFinding(
      String repository, String fp, Severity severity, String path, String message) {
    String runId = "run-" + fp;
    run(runId, repository, finding(fp, severity, path, message));
    transition(repository, fp, null, FindingState.NEW, TransitionCause.FIRST_OBSERVED, runId);
  }

  public void resolve(String repository, String fp) {
    transition(
        repository,
        fp,
        FindingState.OPEN,
        FindingState.RESOLVED,
        TransitionCause.ABSENT_FROM_COMPLETE_RUN,
        "run-" + fp);
  }

  public void regress(String repository, String fp) {
    transition(
        repository,
        fp,
        FindingState.RESOLVED,
        FindingState.REGRESSED,
        TransitionCause.REAPPEARED_AFTER_RESOLUTION,
        "run-" + fp);
  }

  public void suppression(String repository, String fp, String reason, Instant expires) {
    Instant created = expires.minus(Duration.ofDays(20));
    parts
        .suppressions()
        .save(new Suppression("s-" + fp, repository, fp, reason, created, expires, 90, null));
  }

  /** Moves the clock into the middle of the next period (00:30 UTC the next day). */
  public void nextPeriod() {
    long day = 86_400;
    long now = clock.instant().getEpochSecond();
    Instant midnight = Instant.ofEpochSecond((Math.floorDiv(now, day) + 1) * day);
    clock.advance(Duration.between(clock.instant(), midnight.plus(Duration.ofMinutes(30))));
  }

  /** The first tick, which only sets the baseline. */
  public void start() {
    worker.runOnce();
  }

  /** Runs egress until it finds nothing more to do right now. */
  public void pump() {
    for (int i = 0; i < 50; i++) {
      if (worker.runOnce() == 0) {
        return;
      }
    }
    throw new IllegalStateException("egress never went idle");
  }
}
