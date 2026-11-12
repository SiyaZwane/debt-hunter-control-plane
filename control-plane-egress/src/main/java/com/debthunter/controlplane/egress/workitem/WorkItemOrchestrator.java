package com.debthunter.controlplane.egress.workitem;

import com.debthunter.controlplane.egress.outbox.DeliveryUnavailableException;
import com.debthunter.controlplane.egress.outbox.UndeliverableException;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.findings.PseudonymisedFindingStore;
import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.lifecycle.FindingTransitionEvent;
import com.debthunter.controlplane.store.lifecycle.TransitionCause;
import com.debthunter.controlplane.store.lifecycle.TransitionLogRepository;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.registry.RepositoryRegistry;
import com.debthunter.controlplane.store.registry.WorkItemRoute;
import com.debthunter.controlplane.store.rollout.RolloutStage;
import com.debthunter.controlplane.store.rollout.StageLookup;
import com.debthunter.controlplane.store.suppressions.SuppressionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps each finding's work item in line with the finding, with exactly one item per finding.
 *
 * <p>It does not replay events. Given "look at this finding", it reads the finding's state now and
 * does the one thing that brings the tracker into line with it, so being asked twice, or being
 * asked about an old event after a newer one, does no harm.
 *
 * <ul>
 *   <li><b>Open or regressed:</b> if the finding has an item, it is reopened if it is not open. If
 *       it has none, one is created, but only when the repository is at a stage that creates work
 *       items and the finding is at least as severe as the threshold.
 *   <li><b>Resolved:</b> the finding's item is closed.
 *   <li><b>Suppressed:</b> the item is put on hold, with the suppression's reason and end date in a
 *       comment. When the suppression ends and the finding comes back open, the item is reopened.
 * </ul>
 *
 * <p>A finding never gets a second item. The link between finding and item is reserved before the
 * tracker is called, and the tracker is searched for the finding's key before an item is created,
 * so an attempt that created the item but died before recording it is picked up, not repeated. Only
 * the creation of an item is gated by stage and severity: an item that exists keeps being kept in
 * line, even if its repository has since been moved back a stage.
 *
 * <p>Whatever stops the work through no fault of its own (no route for the repository, no adapter
 * for its tracker, a tracker that is down, another worker holding the finding) throws {@link
 * DeliveryUnavailableException}, so the message is held and not lost.
 */
public final class WorkItemOrchestrator {

  private static final Logger LOG = LoggerFactory.getLogger(WorkItemOrchestrator.class);

  private final TransitionLogRepository log;
  private final WorkItemLinkRepository links;
  private final PseudonymisedFindingStore findings;
  private final SuppressionRepository suppressions;
  private final RepositoryRegistry registry;
  private final StageLookup stages;
  private final WorkItemTrackers trackers;
  private final RunLinks runLinks;
  private final WorkItemSettings settings;
  private final Clock clock;
  private final String owner;

  /**
   * Creates the orchestrator.
   *
   * @param log the transition log, which says what state a finding is in
   * @param links the finding-to-item links
   * @param findings the finding store, for the details of a new item
   * @param suppressions the suppression register, for the comment on an item put on hold
   * @param registry the repository registry, which holds each repository's route
   * @param stages the rollout stages
   * @param trackers the trackers this deployment can talk to
   * @param runLinks where to look at a run
   * @param settings the severity threshold and claim time
   * @param clock the source of "now"
   * @param owner who this orchestrator is, for claims
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public WorkItemOrchestrator(
      TransitionLogRepository log,
      WorkItemLinkRepository links,
      PseudonymisedFindingStore findings,
      SuppressionRepository suppressions,
      RepositoryRegistry registry,
      StageLookup stages,
      WorkItemTrackers trackers,
      RunLinks runLinks,
      WorkItemSettings settings,
      Clock clock,
      String owner) {
    this.log = Objects.requireNonNull(log, "log");
    this.links = Objects.requireNonNull(links, "links");
    this.findings = Objects.requireNonNull(findings, "findings");
    this.suppressions = Objects.requireNonNull(suppressions, "suppressions");
    this.registry = Objects.requireNonNull(registry, "registry");
    this.stages = Objects.requireNonNull(stages, "stages");
    this.trackers = Objects.requireNonNull(trackers, "trackers");
    this.runLinks = Objects.requireNonNull(runLinks, "runLinks");
    this.settings = Objects.requireNonNull(settings, "settings");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.owner = Objects.requireNonNull(owner, "owner");
  }

  /**
   * Brings a finding's work item into line with the finding.
   *
   * @param job which finding, and which run the news came from
   * @return what was done
   * @throws DeliveryUnavailableException if it cannot be done now through no fault of the finding
   * @throws UndeliverableException if the finding has no history to act on
   */
  public SyncResult reconcile(SyncJob job) {
    List<FindingTransitionEvent> history =
        log.historyOf(job.repository(), job.projectSlice(), job.fingerprint());
    if (history.isEmpty()) {
      throw new UndeliverableException("the finding has no recorded history");
    }
    FindingTransitionEvent latest = history.get(history.size() - 1);
    WorkItemStatus desired = desiredStatus(latest.toState());
    if (desired == null) {
      return SyncResult.NO_ACTION;
    }

    Optional<WorkItemLink> existing =
        links.find(job.repository(), job.projectSlice(), job.fingerprint());
    boolean known = existing.isPresent() && existing.get().hasItem();
    if (known && existing.get().status() == desired) {
      return SyncResult.NO_ACTION;
    }

    PseudonymisedFinding toCreateFrom = null;
    if (!known) {
      boolean mayHaveOne = existing.isPresent();
      if (desired != WorkItemStatus.OPEN && !mayHaveOne) {
        return SyncResult.NO_ACTION;
      }
      if (desired == WorkItemStatus.OPEN) {
        SyncResult gate = gate(job);
        if (gate != null) {
          return gate;
        }
        toCreateFrom = detailsOf(job);
      }
    }

    Route route = routeOf(job.repository());
    String key = WorkItemKey.of(job.repository(), job.projectSlice(), job.fingerprint());
    links.reserve(
        job.repository(),
        job.projectSlice(),
        job.fingerprint(),
        key,
        route.name(),
        clock.instant());
    Instant now = clock.instant();
    if (!links.claim(
        job.repository(),
        job.projectSlice(),
        job.fingerprint(),
        owner,
        now,
        now.plus(settings.claimTtl()))) {
      throw new DeliveryUnavailableException(
          "another worker is already handling this finding's work item", null);
    }
    try {
      return write(job, latest, desired, route, key, toCreateFrom);
    } finally {
      links.release(job.repository(), job.projectSlice(), job.fingerprint(), owner);
    }
  }

  private SyncResult write(
      SyncJob job,
      FindingTransitionEvent latest,
      WorkItemStatus desired,
      Route route,
      String key,
      PseudonymisedFinding toCreateFrom) {
    // Read again now that the finding is claimed: another worker may have got here first.
    WorkItemLink link =
        links.find(job.repository(), job.projectSlice(), job.fingerprint()).orElseThrow();
    SyncResult result = SyncResult.NO_ACTION;

    if (!link.hasItem()) {
      Optional<TrackedItem> found = route.tracker().find(route.route(), key);
      if (found.isPresent()) {
        link = link.withItem(found.get(), found.get().status(), clock.instant());
        links.save(link);
        result = SyncResult.ADOPTED;
      } else if (desired == WorkItemStatus.OPEN) {
        PseudonymisedFinding finding = toCreateFrom != null ? toCreateFrom : detailsOf(job);
        TrackedItem created =
            route
                .tracker()
                .create(
                    route.route(),
                    WorkItemText.content(key, finding, runLinks.linkTo(job.runId())));
        links.save(link.withItem(created, WorkItemStatus.OPEN, clock.instant()));
        return SyncResult.CREATED;
      } else {
        return SyncResult.NO_ACTION;
      }
    }

    if (link.status() == desired) {
      return result;
    }
    route.tracker().move(route.route(), link.itemId(), desired, comment(desired, latest, job));
    links.save(link.withStatus(desired, clock.instant()));
    return switch (desired) {
      case OPEN -> SyncResult.REOPENED;
      case CLOSED -> SyncResult.CLOSED;
      case ON_HOLD -> SyncResult.PUT_ON_HOLD;
    };
  }

  /** Whether an item may be created: the repository's stage and the finding's severity. */
  private SyncResult gate(SyncJob job) {
    RolloutStage stage = stages.currentStages().get(job.repository());
    if (stage == null || !stage.createsWorkItems()) {
      return SyncResult.SKIPPED_BELOW_STAGE;
    }
    PseudonymisedFinding finding = detailsOf(job);
    if (!settings.qualifies(finding.severity())) {
      return SyncResult.SKIPPED_BELOW_SEVERITY;
    }
    return null;
  }

  private PseudonymisedFinding detailsOf(SyncJob job) {
    return findings.findingsForRun(job.runId()).stream()
        .filter(f -> f.fingerprint().equals(job.fingerprint()))
        .findFirst()
        .orElseThrow(
            () -> new UndeliverableException("the finding is not in the run that reported it"));
  }

  private String comment(WorkItemStatus desired, FindingTransitionEvent latest, SyncJob job) {
    return switch (desired) {
      case CLOSED -> WorkItemText.closed();
      case ON_HOLD -> WorkItemText.onHold(suppressions.find(job.repository(), job.fingerprint()));
      case OPEN ->
          latest.cause() == TransitionCause.REAPPEARED_AFTER_RESOLUTION
              ? WorkItemText.reopenedRegression()
              : latest.cause() == TransitionCause.SUPPRESSION_EXPIRED
                  ? WorkItemText.reopenedSuppressionEnded()
                  : WorkItemText.reopened();
    };
  }

  private Route routeOf(String repository) {
    Optional<OnboardedRepository> onboarded = registry.find(repository);
    if (onboarded.isEmpty()) {
      LOG.warn("no routing entry for {}; its work items are held until there is one", repository);
      throw new DeliveryUnavailableException("the repository has no routing entry", null);
    }
    WorkItemRoute route = onboarded.get().route();
    Optional<WorkItemTracker> tracker = trackers.named(route.tracker());
    if (tracker.isEmpty()) {
      LOG.warn(
          "no tracker adapter named {} for {}; its work items are held",
          route.tracker(),
          repository);
      throw new DeliveryUnavailableException("no tracker is configured for the route", null);
    }
    return new Route(route, route.tracker(), tracker.get());
  }

  private static WorkItemStatus desiredStatus(FindingState state) {
    return switch (state) {
      case OPEN, REGRESSED -> WorkItemStatus.OPEN;
      case RESOLVED -> WorkItemStatus.CLOSED;
      case SUPPRESSED -> WorkItemStatus.ON_HOLD;
      case NEW -> null;
    };
  }

  private record Route(WorkItemRoute route, String name, WorkItemTracker tracker) {}
}
