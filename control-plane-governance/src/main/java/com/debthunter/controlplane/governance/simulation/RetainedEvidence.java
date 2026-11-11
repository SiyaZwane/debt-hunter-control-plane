package com.debthunter.controlplane.governance.simulation;

import com.debthunter.controlplane.store.findings.PseudonymisedFinding;
import com.debthunter.controlplane.store.registry.OnboardedRepository;
import com.debthunter.controlplane.store.runs.RunRecord;
import java.time.Instant;
import java.util.List;

/**
 * Everything the simulator is allowed to know, and nothing it could change. Every method is a read.
 *
 * <p>This is deliberately a narrow port rather than the stores themselves. The stores have write
 * methods; handing the simulator a type that has none makes "a simulation mutates nothing" a fact
 * about the code's shape rather than a promise about its behaviour.
 */
public interface RetainedEvidence {

  /**
   * Every onboarded repository: the estate a policy would apply to.
   *
   * @return the registry's repositories
   */
  List<OnboardedRepository> estate();

  /**
   * A repository's most recent runs, newest first, of any status.
   *
   * @param repository the repository identifier
   * @param limit the most runs to return
   * @return the runs
   */
  List<RunRecord> recentRuns(String repository, int limit);

  /**
   * The findings a run recorded.
   *
   * @param runId the run
   * @return the findings, empty if the run observed none
   */
  List<PseudonymisedFinding> findingsOf(String runId);

  /**
   * Whether a finding was under an active suppression at a given moment.
   *
   * @param repository the repository identifier
   * @param fingerprint the finding's fingerprint
   * @param at the moment to judge it at, normally the run's commit date
   * @return {@code true} if a suppression applied then
   */
  boolean suppressedAt(String repository, String fingerprint, Instant at);
}
