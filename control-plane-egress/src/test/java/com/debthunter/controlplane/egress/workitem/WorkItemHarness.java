package com.debthunter.controlplane.egress.workitem;

import com.debthunter.controlplane.egress.MutableClock;
import com.debthunter.controlplane.egress.alert.RecordingAlerts;
import com.debthunter.controlplane.egress.outbox.EgressCheckpoints;
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
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Everything work-item egress needs, wired together over whichever stores a test supplies, with a
 * fake tracker, a clock the test moves, and helpers to say what happened to a finding.
 */
public class WorkItemHarness {

  public static final String REPO = "acme/widgets";
  public static final String SLICE = "root";
  public static final String ROUTE_TRACKER = "fake";
  public static final String CONTRIBUTOR = "c".repeat(64);

  /** The stores a harness runs over. */
  public record Stores(
      TransitionLogRepository log,
      PseudonymisedFindingStore findings,
      SuppressionRepository suppressions,
      RepositoryRegistry registry,
      WorkItemLinkRepository links,
      EgressCheckpoints checkpoints,
      WorkQueue queue) {}

  public final MutableClock clock;
  public final FakeTracker tracker = new FakeTracker();
  public final RecordingAlerts alerts = new RecordingAlerts();
  public final Map<String, RolloutStage> stages = new HashMap<>();
  public final Stores stores;
  public final TransactionalOutbox outbox;
  public final WorkItemFeeder feeder;
  public final WorkItemOrchestrator orchestrator;
  public final WorkItemEgressWorker worker;
  public final WorkItemSettings settings;
  private final AtomicInteger events = new AtomicInteger();

  public WorkItemHarness(Stores stores, MutableClock clock) {
    this(stores, clock, WorkItemSettings.defaults());
  }

  public WorkItemHarness(Stores stores, MutableClock clock, WorkItemSettings settings) {
    this.stores = stores;
    this.clock = clock;
    this.settings = settings;
    outbox = new TransactionalOutbox(stores.queue(), WorkItemQueue.KIND, "w1", 20, alerts, clock);
    feeder = new WorkItemFeeder(stores.log(), stores.checkpoints(), outbox, 50);
    orchestrator =
        new WorkItemOrchestrator(
            stores.log(),
            stores.links(),
            stores.findings(),
            stores.suppressions(),
            stores.registry(),
            () -> stages,
            new WorkItemTrackers(Map.of(ROUTE_TRACKER, tracker)),
            runId -> java.util.Optional.of("https://cp.example/runs/" + runId),
            settings,
            clock,
            "w1");
    worker =
        new WorkItemEgressWorker(
            feeder, outbox, new WorkItemSyncHandler(orchestrator), Duration.ofMillis(10));
    stages.put(REPO, RolloutStage.STAGE_3);
    onboard(REPO, new WorkItemRoute(ROUTE_TRACKER, "DH"));
  }

  public void onboard(String repository, WorkItemRoute route) {
    stores
        .registry()
        .save(new OnboardedRepository(repository, "team-a", "tier-1", "v1", "STAGE_3", route));
  }

  public static PseudonymisedFinding finding(String fingerprint, Severity severity) {
    return new PseudonymisedFinding(
        "f-" + fingerprint,
        "codemaat.hotspot",
        Category.HOTSPOT,
        severity,
        0.8,
        "src/Foo.java",
        7,
        "src/Foo.java is a change hotspot",
        Map.of("changeFrequency", 42.0),
        0.6,
        true,
        fingerprint,
        List.of(Pseudonym.fromStored(CONTRIBUTOR)));
  }

  /** Stores the finding as reported by a run. */
  public void run(String runId, PseudonymisedFinding... found) {
    stores.findings().saveRun(runId, REPO, List.of(found));
  }

  /** Appends a transition of a finding to the log, as the lifecycle would. */
  public FindingTransitionEvent transition(
      String fingerprint, FindingState from, FindingState to, TransitionCause cause, String runId) {
    FindingTransitionEvent event =
        new FindingTransitionEvent(
            "ev-" + events.incrementAndGet(),
            REPO,
            SLICE,
            fingerprint,
            from,
            to,
            runId,
            clock.instant(),
            cause);
    stores.log().append(List.of(event));
    return event;
  }

  public void firstSeen(String fp, String runId) {
    transition(fp, null, FindingState.NEW, TransitionCause.FIRST_OBSERVED, runId);
  }

  public void opened(String fp, String runId) {
    transition(fp, FindingState.NEW, FindingState.OPEN, TransitionCause.OBSERVED_AGAIN, runId);
  }

  public void resolved(String fp, String runId) {
    transition(
        fp,
        FindingState.OPEN,
        FindingState.RESOLVED,
        TransitionCause.ABSENT_FROM_COMPLETE_RUN,
        runId);
  }

  public void regressed(String fp, String runId) {
    transition(
        fp,
        FindingState.RESOLVED,
        FindingState.REGRESSED,
        TransitionCause.REAPPEARED_AFTER_RESOLUTION,
        runId);
  }

  public void suppressed(String fp, String runId) {
    transition(
        fp, FindingState.OPEN, FindingState.SUPPRESSED, TransitionCause.SUPPRESSION_APPLIED, runId);
  }

  public void suppressionExpired(String fp, String runId) {
    transition(
        fp, FindingState.SUPPRESSED, FindingState.OPEN, TransitionCause.SUPPRESSION_EXPIRED, runId);
  }

  public void suppression(String fp, String reason, Duration lasts) {
    stores
        .suppressions()
        .save(
            new Suppression(
                "s-" + fp,
                REPO,
                fp,
                reason,
                clock.instant(),
                clock.instant().plus(lasts),
                90,
                null));
  }

  /** Runs egress until it finds nothing more to do right now. Returns the passes that did work. */
  public int pump() {
    int worked = 0;
    for (int i = 0; i < 50; i++) {
      if (worker.runOnce() == 0) {
        return worked;
      }
      worked++;
    }
    throw new IllegalStateException("egress never went idle");
  }
}
