package com.debthunter.controlplane.store.runs;

import java.util.List;
import java.util.Optional;

/** The run store: metadata of every run applied, including degraded flag and engine statuses. */
public interface RunRepository {

  /**
   * Looks a run up by its own id.
   *
   * @param runId the run id
   * @return the run, or empty if it has not been applied
   */
  Optional<RunRecord> find(String runId);

  /**
   * Finds the runs sharing a composite key: earlier runs of the same repository, commit, slice and
   * tool version.
   *
   * @param compositeKey the key
   * @return the runs, oldest first
   */
  List<RunRecord> findByCompositeKey(String compositeKey);

  /**
   * The run most recently applied to a slice's lifecycle state.
   *
   * @param repository the repository
   * @param projectSlice the project slice
   * @return the run that last moved state, or empty if none has
   */
  Optional<RunRecord> latestApplied(String repository, String projectSlice);

  /**
   * Saves a run and, in the same operation, marks earlier runs of the same composite key as
   * superseded by it. Idempotent for the same run id.
   *
   * @param run the run
   */
  void save(RunRecord run);
}
