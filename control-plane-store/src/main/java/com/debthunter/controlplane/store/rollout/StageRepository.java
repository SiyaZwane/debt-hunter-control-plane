package com.debthunter.controlplane.store.rollout;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for rollout stage records and their event log.
 *
 * <p>A change to a record and the event that explains it are written together or not at all, and a
 * change is refused if the repository is no longer where the change assumed it was, so two
 * approvers cannot both advance the same repository from the same stage.
 */
public interface StageRepository extends StageLookup {

  /** Looks a repository's record up. */
  Optional<StageRecord> find(String repository);

  /** Every record, ordered by repository. */
  List<StageRecord> all();

  /**
   * Writes a record and the event that explains it, atomically.
   *
   * @param updated the new record
   * @param event the event. For {@link StageEvent.Kind#ENROLLED} the repository must have no
   *     record; otherwise its current stage must equal {@code event.fromStage()}.
   * @throws StageConflictException if that precondition does not hold
   */
  void apply(StageRecord updated, StageEvent event);

  /**
   * Writes a record without an event, for bookkeeping that is not a change of stage (such as noting
   * that an escalation was sent). The repository must already have a record.
   *
   * @param updated the new record
   * @throws StageConflictException if the repository has no record
   */
  void update(StageRecord updated);

  /** A repository's events, oldest first. */
  List<StageEvent> events(String repository);
}
