package com.debthunter.controlplane.governance.rollout;

import com.debthunter.controlplane.store.rollout.RolloutStage;
import com.debthunter.controlplane.store.rollout.StageConflictException;
import com.debthunter.controlplane.store.rollout.StageEvent;
import com.debthunter.controlplane.store.rollout.StageExemption;
import com.debthunter.controlplane.store.rollout.StageRecord;
import com.debthunter.controlplane.store.rollout.StageRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps each repository's place in the rollout, moves it only on evidence, and notices when it has
 * stayed too long.
 *
 * <p>Moving forward is one stage at a time and needs every exit criterion for the stage being left,
 * each with evidence, an approving authority and a date; the event is recorded with the change or
 * not at all. Moving back needs only a reason. Nothing here runs on a pipeline's path.
 */
public final class StageManager {

  private static final Logger LOG = LoggerFactory.getLogger(StageManager.class);

  private final StageRepository stages;
  private final StagePolicy policy;
  private final StageEscalationSink escalations;

  /**
   * Creates the manager.
   *
   * @param stages where records and events are kept
   * @param policy the rules of the rollout
   * @param escalations where escalations go
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public StageManager(StageRepository stages, StagePolicy policy, StageEscalationSink escalations) {
    this.stages = Objects.requireNonNull(stages, "stages");
    this.policy = Objects.requireNonNull(policy, "policy");
    this.escalations = Objects.requireNonNull(escalations, "escalations");
  }

  /**
   * Puts a repository into the rollout at stage 1.
   *
   * @param repository the repository
   * @param owner the named owner accountable for its progress
   * @param approvedBy who enrolled it
   * @param at when
   * @return the outcome
   */
  public StageOutcome enrol(String repository, String owner, String approvedBy, Instant at) {
    StageRecord record =
        new StageRecord(repository, RolloutStage.STAGE_1, at, owner, Map.of(), null, null);
    StageEvent event =
        new StageEvent(
            repository,
            StageEvent.Kind.ENROLLED,
            null,
            RolloutStage.STAGE_1,
            at,
            approvedBy,
            Map.of(),
            null);
    return write(record, event, StageOutcome.Reason.ALREADY_ENROLLED);
  }

  /**
   * Moves a repository forward one stage.
   *
   * @param repository the repository
   * @param approvedBy the approving authority
   * @param at when
   * @param evidence for each exit criterion of the stage being left, the evidence that it is met
   * @return the outcome
   */
  public StageOutcome advance(
      String repository, String approvedBy, Instant at, Map<String, String> evidence) {
    Optional<StageRecord> found = stages.find(repository);
    if (found.isEmpty()) {
      return notEnrolled(repository);
    }
    StageRecord current = found.get();
    RolloutStage next = current.stage().next();
    if (next == null) {
      return refuse(
          StageOutcome.Reason.NO_NEXT_STAGE, repository + " is already at " + current.stage());
    }
    if (at.isBefore(current.effectiveFrom())) {
      return refuse(
          StageOutcome.Reason.BEFORE_CURRENT_STAGE,
          "Cannot move " + repository + " at " + at + ", before it entered " + current.stage());
    }
    Set<String> missing = new TreeSet<>();
    for (String criterion : policy.exitCriteria(current.stage())) {
      String proof = evidence == null ? null : evidence.get(criterion);
      if (proof == null || proof.isBlank()) {
        missing.add(criterion);
      }
    }
    if (!missing.isEmpty()) {
      return new StageOutcome.Refused(
          StageOutcome.Reason.MISSING_CRITERIA,
          "To leave " + current.stage() + " the following need evidence: " + missing,
          missing);
    }
    // Keep only the evidence for the criteria that were required, so the record says what was
    // relied on.
    Map<String, String> relied = new java.util.TreeMap<>();
    for (String criterion : policy.exitCriteria(current.stage())) {
      relied.put(criterion, evidence.get(criterion));
    }
    StageRecord updated =
        new StageRecord(repository, next, at, current.owner(), relied, null, null);
    StageEvent event =
        new StageEvent(
            repository,
            StageEvent.Kind.ADVANCED,
            current.stage(),
            next,
            at,
            approvedBy,
            relied,
            null);
    return write(updated, event, StageOutcome.Reason.CONFLICT);
  }

  /**
   * Moves a repository back to an earlier stage.
   *
   * @param repository the repository
   * @param to the earlier stage
   * @param approvedBy the approving authority
   * @param reason why; required
   * @param at when
   * @return the outcome
   */
  public StageOutcome regress(
      String repository, RolloutStage to, String approvedBy, String reason, Instant at) {
    if (reason == null || reason.isBlank()) {
      throw new IllegalArgumentException("Moving a repository back needs a reason");
    }
    Optional<StageRecord> found = stages.find(repository);
    if (found.isEmpty()) {
      return notEnrolled(repository);
    }
    StageRecord current = found.get();
    if (to.level() >= current.stage().level()) {
      return refuse(
          StageOutcome.Reason.NOT_EARLIER,
          to + " is not earlier than " + current.stage() + " for " + repository);
    }
    if (at.isBefore(current.effectiveFrom())) {
      return refuse(
          StageOutcome.Reason.BEFORE_CURRENT_STAGE,
          "Cannot move " + repository + " at " + at + ", before it entered " + current.stage());
    }
    StageRecord updated =
        new StageRecord(repository, to, at, current.owner(), Map.of(), null, null);
    StageEvent event =
        new StageEvent(
            repository,
            StageEvent.Kind.REGRESSED,
            current.stage(),
            to,
            at,
            approvedBy,
            Map.of(),
            reason);
    return write(updated, event, StageOutcome.Reason.CONFLICT);
  }

  /**
   * Approves an exemption from the maximum hold, so the repository may stay where it is until it
   * expires.
   *
   * @param repository the repository
   * @param reason why it may stay
   * @param approvedBy who approved
   * @param at when
   * @param expiresAt when the exemption stops excusing the hold
   * @return the outcome
   */
  public StageOutcome grantExemption(
      String repository, String reason, String approvedBy, Instant at, Instant expiresAt) {
    StageExemption exemption = new StageExemption(reason, approvedBy, at, expiresAt);
    Optional<StageRecord> found = stages.find(repository);
    if (found.isEmpty()) {
      return notEnrolled(repository);
    }
    StageRecord current = found.get();
    StageRecord updated = current.withExemption(exemption);
    StageEvent event =
        new StageEvent(
            repository,
            StageEvent.Kind.EXEMPTION_GRANTED,
            current.stage(),
            current.stage(),
            at,
            approvedBy,
            Map.of(),
            reason);
    return write(updated, event, StageOutcome.Reason.CONFLICT);
  }

  /**
   * Whether work items may be created for a repository: only if it is in the rollout and has
   * reached the stage at which they start.
   *
   * <p>A repository that is not in the rollout is {@code false}: nobody has decided it is ready. If
   * the store cannot be read this throws rather than answering, and a caller must treat that as "do
   * not create now, try again later", never as permission.
   *
   * @param repository the repository
   * @return whether creation is enabled
   */
  public boolean isWorkItemCreationEnabled(String repository) {
    return stages.find(repository).map(r -> r.stage().createsWorkItems()).orElse(false);
  }

  /** A repository's record, if it is in the rollout. */
  public Optional<StageRecord> stageOf(String repository) {
    return stages.find(repository);
  }

  /** A repository's stage events, oldest first. */
  public List<StageEvent> history(String repository) {
    return stages.events(repository);
  }

  /**
   * Escalates every repository that has stayed at a stage longer than allowed.
   *
   * <p>A repository is escalated once per stay: it is marked after delivery, so the next sweep does
   * not repeat it, and moving it to another stage starts a new stay. An approved, unexpired
   * exemption excuses the hold; when it expires the repository becomes due again. If delivery fails
   * the repository is left unmarked and is tried again next sweep, and the other repositories are
   * still processed.
   *
   * @param now the time to judge by
   * @return what was escalated and what could not be
   */
  public EscalationSweepResult escalateOverdue(Instant now) {
    List<StageEscalation> done = new ArrayList<>();
    List<String> failed = new ArrayList<>();
    for (StageRecord record : stages.all()) {
      Duration max = policy.maxHold(record.stage());
      if (max == null
          || record.escalatedAt() != null
          || record.heldFor(now).compareTo(max) <= 0
          || (record.exemption() != null && record.exemption().isActiveAt(now))) {
        continue;
      }
      StageEscalation escalation =
          new StageEscalation(
              record.repository(),
              record.stage(),
              record.owner(),
              record.effectiveFrom(),
              record.heldFor(now),
              max);
      try {
        escalations.emit(escalation);
      } catch (RuntimeException e) {
        LOG.warn("Could not deliver the escalation for {}", record.repository(), e);
        failed.add(record.repository());
        continue;
      }
      try {
        stages.update(record.escalated(now));
        done.add(escalation);
      } catch (RuntimeException e) {
        // Delivered but not recorded: it will be delivered again next sweep, which is the
        // at-least-once trade this sink contract makes.
        LOG.warn("Delivered the escalation for {} but could not record it", record.repository(), e);
        failed.add(record.repository());
      }
    }
    return new EscalationSweepResult(done, failed);
  }

  private StageOutcome write(StageRecord record, StageEvent event, StageOutcome.Reason onConflict) {
    try {
      stages.apply(record, event);
      return new StageOutcome.Changed(record);
    } catch (StageConflictException e) {
      return refuse(onConflict, e.getMessage());
    }
  }

  private static StageOutcome notEnrolled(String repository) {
    return refuse(StageOutcome.Reason.NOT_ENROLLED, repository + " is not in the rollout");
  }

  private static StageOutcome refuse(StageOutcome.Reason reason, String message) {
    return new StageOutcome.Refused(reason, message, Set.of());
  }
}
