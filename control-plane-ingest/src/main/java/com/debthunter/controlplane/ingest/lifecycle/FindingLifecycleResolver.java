package com.debthunter.controlplane.ingest.lifecycle;

import com.debthunter.controlplane.store.lifecycle.FindingState;
import com.debthunter.controlplane.store.lifecycle.FindingStateRecord;
import com.debthunter.controlplane.store.lifecycle.FindingTransitionEvent;
import com.debthunter.controlplane.store.lifecycle.TransitionCause;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The finding lifecycle state machine: given where each finding stands and what a run observed,
 * works out every transition.
 *
 * <p>It is a pure function. It reads nothing and writes nothing, so every rule below can be tested
 * exhaustively without a store, a queue, or a clock.
 *
 * <ul>
 *   <li>Seen for the first time: {@code NEW}. Seen at a later commit: {@code OPEN}.
 *   <li>Absent from a <b>complete</b> run: {@code RESOLVED}. A degraded run cannot resolve
 *       anything: a finding is not gone because the engine that produces it failed.
 *   <li>A resolved finding that reappears is {@code REGRESSED}, which is not {@code NEW}.
 *   <li>A finding under an active suppression is {@code SUPPRESSED} and does not count against a
 *       gate. When the suppression has expired <em>as of the commit date</em> it returns to {@code
 *       OPEN}; the wall clock is never consulted.
 *   <li>Seeing a finding again at the <em>same commit</em> (a re-run) is not a later observation,
 *       so it never moves {@code NEW} to {@code OPEN}.
 * </ul>
 */
public final class FindingLifecycleResolver {

  private final SuppressionLookup suppressions;

  /**
   * Creates a resolver.
   *
   * @param suppressions read access to the suppression register
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public FindingLifecycleResolver(SuppressionLookup suppressions) {
    this.suppressions = suppressions;
  }

  /**
   * Applies one run to a slice.
   *
   * @param repository the repository
   * @param projectSlice the project slice
   * @param runId the run being applied
   * @param commit the commit it analysed
   * @param commitDate the commit's date: the time of every event and the instant suppressions are
   *     judged at
   * @param complete whether the run saw everything (no degraded engine, no scope limitation)
   * @param observed the fingerprints the run reported
   * @param current the slice's current states, by fingerprint
   * @return the transitions and the resulting states
   */
  public ResolutionResult resolve(
      String repository,
      String projectSlice,
      String runId,
      String commit,
      Instant commitDate,
      boolean complete,
      Set<String> observed,
      Map<String, FindingStateRecord> current) {
    Set<String> universe = new TreeSet<>(observed);
    universe.addAll(current.keySet());

    List<FindingTransitionEvent> events = new ArrayList<>();
    List<FindingStateRecord> updated = new ArrayList<>();

    for (String fingerprint : universe) {
      boolean seen = observed.contains(fingerprint);
      FindingStateRecord before = current.get(fingerprint);
      String lastCommit = before == null ? null : before.lastObservedCommit();
      boolean sameCommit = commit.equals(lastCommit);

      FindingState state = before == null ? null : before.state();
      int step = 0;
      List<FindingTransitionEvent> mine = new ArrayList<>();

      // 1. A suppression that has expired as of this commit's date stops applying.
      if (state == FindingState.SUPPRESSED && !suppressedAt(repository, fingerprint, commitDate)) {
        state =
            move(
                mine,
                repository,
                projectSlice,
                fingerprint,
                state,
                FindingState.OPEN,
                runId,
                commitDate,
                TransitionCause.SUPPRESSION_EXPIRED,
                step++);
      }

      // 2. What the run saw.
      if (seen) {
        if (state == null) {
          state =
              move(
                  mine,
                  repository,
                  projectSlice,
                  fingerprint,
                  null,
                  FindingState.NEW,
                  runId,
                  commitDate,
                  TransitionCause.FIRST_OBSERVED,
                  step++);
        } else if (state == FindingState.RESOLVED) {
          state =
              move(
                  mine,
                  repository,
                  projectSlice,
                  fingerprint,
                  state,
                  FindingState.REGRESSED,
                  runId,
                  commitDate,
                  TransitionCause.REAPPEARED_AFTER_RESOLUTION,
                  step++);
        } else if ((state == FindingState.NEW || state == FindingState.REGRESSED) && !sameCommit) {
          state =
              move(
                  mine,
                  repository,
                  projectSlice,
                  fingerprint,
                  state,
                  FindingState.OPEN,
                  runId,
                  commitDate,
                  TransitionCause.OBSERVED_AGAIN,
                  step++);
        }
        // 3. An active suppression takes a live finding out of gating.
        if (state.blocksGating() && suppressedAt(repository, fingerprint, commitDate)) {
          state =
              move(
                  mine,
                  repository,
                  projectSlice,
                  fingerprint,
                  state,
                  FindingState.SUPPRESSED,
                  runId,
                  commitDate,
                  TransitionCause.SUPPRESSION_APPLIED,
                  step++);
        }
      } else if (complete && state.blocksGating()) {
        // 4. Absence only means "gone" when the run could have seen it.
        state =
            move(
                mine,
                repository,
                projectSlice,
                fingerprint,
                state,
                FindingState.RESOLVED,
                runId,
                commitDate,
                TransitionCause.ABSENT_FROM_COMPLETE_RUN,
                step++);
      }

      String observedAt = seen ? commit : lastCommit;
      boolean changed =
          before == null || before.state() != state || (seen && !commit.equals(lastCommit));
      if (changed) {
        events.addAll(mine);
        updated.add(
            new FindingStateRecord(repository, projectSlice, fingerprint, state, observedAt));
      }
    }
    return new ResolutionResult(events, updated);
  }

  private boolean suppressedAt(String repository, String fingerprint, Instant commitDate) {
    return suppressions
        .find(repository, fingerprint)
        .map(s -> s.appliesAt(commitDate))
        .orElse(false);
  }

  private static FindingState move(
      List<FindingTransitionEvent> out,
      String repository,
      String projectSlice,
      String fingerprint,
      FindingState from,
      FindingState to,
      String runId,
      Instant at,
      TransitionCause cause,
      int step) {
    out.add(
        new FindingTransitionEvent(
            runId + '|' + fingerprint + '|' + step,
            repository,
            projectSlice,
            fingerprint,
            from,
            to,
            runId,
            at,
            cause));
    return to;
  }
}
