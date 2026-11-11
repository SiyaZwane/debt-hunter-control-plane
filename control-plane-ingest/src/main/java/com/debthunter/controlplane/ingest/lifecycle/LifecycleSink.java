package com.debthunter.controlplane.ingest.lifecycle;

import com.debthunter.controlplane.ingest.lifecycle.CommitTopologyResolver.Relation;
import com.debthunter.controlplane.ingest.lifecycle.IdempotencyGuard.Decision;
import com.debthunter.controlplane.ingest.pseudonymise.PseudonymisedRunBundle;
import com.debthunter.controlplane.ingest.pseudonymise.PseudonymisedRunSink;
import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.lifecycle.FindingStateRecord;
import com.debthunter.controlplane.store.lifecycle.FindingStateRepository;
import com.debthunter.controlplane.store.lifecycle.TransitionLogRepository;
import com.debthunter.controlplane.store.runs.EngineRecord;
import com.debthunter.controlplane.store.runs.RunRecord;
import com.debthunter.controlplane.store.runs.RunRepository;
import com.debthunter.controlplane.store.runs.RunStatus;
import com.debthunter.controlplane.store.suppressions.SuppressionLookup;
import com.debthunter.domain.AnalysisRun;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Applies a pseudonymised run to the finding lifecycle.
 *
 * <p>In order: discard a redelivery; set a scope-limited run aside; set a late-arriving run aside;
 * otherwise resolve the transitions, write them to the log, update the states, and only then record
 * the run as applied.
 *
 * <p><b>The order of those writes is the point.</b> The log is written first, and if that fails
 * nothing else changes and the exception propagates, so the bundle is held and retried rather than
 * state moving without a record of why. Appending is idempotent per event, and the run is recorded
 * last, so a failure at any later step is repaired by simply running the same bundle again.
 *
 * <p>This assumes one bundle at a time per repository, which the intake queue provides by keying on
 * repository.
 */
public final class LifecycleSink implements PseudonymisedRunSink {

  private static final Logger log = LoggerFactory.getLogger(LifecycleSink.class);

  /** The slice of a repository that has no project name. */
  static final String DEFAULT_SLICE = "default";

  private final IdempotencyGuard guard;
  private final CommitGraph commits;
  private final CommitTopologyResolver topology;
  private final FindingLifecycleResolver resolver;
  private final RunRepository runs;
  private final FindingStateRepository states;
  private final TransitionLogRepository transitions;

  /**
   * Creates the sink.
   *
   * @param runs the run store
   * @param states current finding states
   * @param transitions the append-only transition log
   * @param commits where commit parents and dates come from
   * @param suppressions read access to the suppression register
   */
  @edu.umd.cs.findbugs.annotations.SuppressFBWarnings(
      value = "EI_EXPOSE_REP2",
      justification = "collaborators are shared services, held by reference intentionally")
  public LifecycleSink(
      RunRepository runs,
      FindingStateRepository states,
      TransitionLogRepository transitions,
      CommitGraph commits,
      SuppressionLookup suppressions) {
    this.runs = Objects.requireNonNull(runs, "runs");
    this.states = Objects.requireNonNull(states, "states");
    this.transitions = Objects.requireNonNull(transitions, "transitions");
    this.commits = Objects.requireNonNull(commits, "commits");
    this.guard = new IdempotencyGuard(runs);
    this.topology = new CommitTopologyResolver(commits);
    this.resolver = new FindingLifecycleResolver(suppressions);
  }

  @Override
  public void accept(PseudonymisedRunBundle bundle) {
    AnalysisRun run = bundle.run();
    String slice = run.project() == null || run.project().isBlank() ? DEFAULT_SLICE : run.project();
    String compositeKey =
        IdempotencyGuard.compositeKey(run.repository(), run.commit(), slice, run.toolVersion());

    Decision decision = guard.classify(run.id(), compositeKey);
    if (decision == Decision.DUPLICATE) {
      log.info("discarding already-applied run runId={}", run.id());
      return;
    }

    Instant committedAt =
        commits
            .node(run.repository(), run.commit())
            .map(CommitNode::committedAt)
            .orElse(run.timestamp());
    boolean scopeLimited = run.baseCommit() != null || run.pullRequest() != null;

    if (scopeLimited) {
      // A diff-scoped run saw only part of the code: it cannot say anything is gone, and it is not
      // a point in the main history, so it moves no state. It is recorded and nothing more.
      runs.save(record(bundle, slice, compositeKey, committedAt, RunStatus.SCOPE_LIMITED));
      return;
    }

    Optional<RunRecord> latest = runs.latestApplied(run.repository(), slice);
    if (latest.isPresent()) {
      Relation relation = topology.relate(run.repository(), run.commit(), latest.get().commit());
      if (relation == Relation.ANCESTOR) {
        // It describes the past. Applying it now would undo what a later commit already settled.
        log.info("run arrived late runId={} commit={}", run.id(), run.commit());
        runs.save(record(bundle, slice, compositeKey, committedAt, RunStatus.LATE));
        return;
      }
      if (relation == Relation.UNKNOWN) {
        log.warn(
            "commit order unknown, applying in arrival order runId={} commit={} applied={}",
            run.id(),
            run.commit(),
            latest.get().commit());
      }
    }

    Set<String> observed =
        bundle.findings().stream()
            .map(PseudonymisedFinding::fingerprint)
            .collect(Collectors.toSet());
    Map<String, FindingStateRecord> current = states.statesFor(run.repository(), slice);
    boolean complete = !run.degraded();

    ResolutionResult result =
        resolver.resolve(
            run.repository(),
            slice,
            run.id(),
            run.commit(),
            committedAt,
            complete,
            observed,
            current);

    transitions.append(result.events());
    states.putAll(result.updatedStates());
    runs.save(record(bundle, slice, compositeKey, committedAt, RunStatus.ACTIVE));
  }

  private static RunRecord record(
      PseudonymisedRunBundle bundle,
      String slice,
      String compositeKey,
      Instant committedAt,
      RunStatus status) {
    AnalysisRun run = bundle.run();
    return new RunRecord(
        run.id(),
        run.repository(),
        slice,
        run.commit(),
        run.toolVersion(),
        compositeKey,
        run.degraded(),
        run.baseCommit() != null || run.pullRequest() != null,
        run.engines().stream().map(e -> new EngineRecord(e.id(), e.status().name())).toList(),
        committedAt,
        status,
        null,
        bundle.policy().status().name());
  }
}
