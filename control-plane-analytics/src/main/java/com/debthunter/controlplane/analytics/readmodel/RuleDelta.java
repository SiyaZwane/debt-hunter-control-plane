package com.debthunter.controlplane.analytics.readmodel;

import java.time.LocalDate;
import java.util.Objects;

/**
 * Transitions for one rule on one day. Added to what is already stored, so a batch of events is a
 * set of increments.
 *
 * @param repository the repository
 * @param projectSlice the project slice
 * @param ruleId the rule
 * @param day the UTC day, by commit date
 * @param created findings first observed
 * @param resolved findings resolved
 * @param regressed findings that came back after being resolved
 */
public record RuleDelta(
    String repository,
    String projectSlice,
    String ruleId,
    LocalDate day,
    int created,
    int resolved,
    int regressed) {

  /** Validates the components. */
  public RuleDelta {
    Objects.requireNonNull(repository, "repository");
    Objects.requireNonNull(projectSlice, "projectSlice");
    Objects.requireNonNull(ruleId, "ruleId");
    Objects.requireNonNull(day, "day");
  }
}
