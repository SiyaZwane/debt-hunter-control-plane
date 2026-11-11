package com.debthunter.controlplane.store.runs;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * Metadata of a run the control plane has applied.
 *
 * @param runId the run's own id
 * @param repository the repository
 * @param projectSlice the project slice
 * @param commit the commit analysed
 * @param toolVersion the data-plane version
 * @param compositeKey digest of repository, commit, slice and tool version, which identifies a
 *     re-run
 * @param degraded whether any engine did not complete cleanly
 * @param scopeLimited whether the run covered only part of the code
 * @param engines each engine's outcome
 * @param committedAt the commit date the run was ordered and evaluated by
 * @param status what became of it
 * @param supersededBy the run that replaced it, if any
 * @param policyStatus the policy outcome the run reported ({@code PASSED}, {@code FAILED} or {@code
 *     WOULD_FAIL}), or {@code null} if not recorded
 * @param receivedAt when the control plane received the bundle (as opposed to when the commit was
 *     made), or {@code null} if not recorded. Coverage tracking measures silence against this
 * @param historyDepth how much Git history the analysis had ({@code FULL}, {@code PARTIAL} or
 *     {@code SHALLOW}), or {@code null} if not recorded
 */
public record RunRecord(
    String runId,
    String repository,
    String projectSlice,
    String commit,
    String toolVersion,
    String compositeKey,
    boolean degraded,
    boolean scopeLimited,
    List<EngineRecord> engines,
    Instant committedAt,
    RunStatus status,
    String supersededBy,
    String policyStatus,
    Instant receivedAt,
    String historyDepth) {

  /** A run with a recorded policy outcome but no reception time or history depth. */
  public RunRecord(
      String runId,
      String repository,
      String projectSlice,
      String commit,
      String toolVersion,
      String compositeKey,
      boolean degraded,
      boolean scopeLimited,
      List<EngineRecord> engines,
      Instant committedAt,
      RunStatus status,
      String supersededBy,
      String policyStatus) {
    this(
        runId,
        repository,
        projectSlice,
        commit,
        toolVersion,
        compositeKey,
        degraded,
        scopeLimited,
        engines,
        committedAt,
        status,
        supersededBy,
        policyStatus,
        null,
        null);
  }

  /** A run with none of the optional facts recorded. */
  public RunRecord(
      String runId,
      String repository,
      String projectSlice,
      String commit,
      String toolVersion,
      String compositeKey,
      boolean degraded,
      boolean scopeLimited,
      List<EngineRecord> engines,
      Instant committedAt,
      RunStatus status,
      String supersededBy) {
    this(
        runId,
        repository,
        projectSlice,
        commit,
        toolVersion,
        compositeKey,
        degraded,
        scopeLimited,
        engines,
        committedAt,
        status,
        supersededBy,
        null,
        null,
        null);
  }

  /** Validates and defensively copies the components. */
  public RunRecord {
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(projectSlice, "projectSlice");
    Objects.requireNonNull(commit, "commit");
    Objects.requireNonNull(compositeKey, "compositeKey");
    Objects.requireNonNull(status, "status");
    engines = engines == null ? List.of() : List.copyOf(engines);
  }

  /** Whether the run saw everything, and so may conclude that an absent finding is gone. */
  public boolean isComplete() {
    return !degraded && !scopeLimited;
  }

  /** A copy marked as replaced. */
  public RunRecord supersededBy(String newerRunId) {
    return new RunRecord(
        runId,
        repository,
        projectSlice,
        commit,
        toolVersion,
        compositeKey,
        degraded,
        scopeLimited,
        engines,
        committedAt,
        RunStatus.SUPERSEDED,
        newerRunId,
        policyStatus,
        receivedAt,
        historyDepth);
  }
}
