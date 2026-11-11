package com.debthunter.controlplane.analytics.readmodel;

import java.time.Instant;
import java.util.Objects;

/**
 * What one commit looked like: its findings by severity, and the quantities trends are drawn from.
 *
 * @param repository the repository
 * @param projectSlice the project slice
 * @param commit the commit analysed
 * @param runId the run that analysed it
 * @param committedAt the commit date, which orders the series
 * @param critical findings of critical severity
 * @param high findings of high severity
 * @param medium findings of medium severity
 * @param low findings of low severity
 * @param info findings of informational severity
 * @param architectureViolations findings in the architecture category
 * @param complexityDelta the sum of the findings' reported complexity change
 * @param policyStatus the policy outcome the run reported, or {@code null}
 */
public record RunSnapshot(
    String repository,
    String projectSlice,
    String commit,
    String runId,
    Instant committedAt,
    int critical,
    int high,
    int medium,
    int low,
    int info,
    int architectureViolations,
    double complexityDelta,
    String policyStatus) {

  /** Validates the components. */
  public RunSnapshot {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(projectSlice, "projectSlice");
    Objects.requireNonNull(commit, "commit");
    Objects.requireNonNull(runId, "runId");
    Objects.requireNonNull(committedAt, "committedAt");
  }

  /** All findings, whatever their severity. */
  public int total() {
    return critical + high + medium + low + info;
  }
}
