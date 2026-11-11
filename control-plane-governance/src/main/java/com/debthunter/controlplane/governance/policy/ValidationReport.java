package com.debthunter.controlplane.governance.policy;

import com.debthunter.policy.PolicyBundle;
import java.util.List;
import java.util.Optional;

/**
 * The verdict on a candidate policy bundle: every issue found, and the parsed bundle if it got far
 * enough to be parsed.
 *
 * @param issues everything found, errors first
 * @param bundle the parsed bundle, or {@code null} when the text could not be parsed at all
 */
public record ValidationReport(List<PolicyIssue> issues, PolicyBundle bundle) {

  /** Defensively copies the issues. */
  public ValidationReport {
    issues = issues == null ? List.of() : List.copyOf(issues);
  }

  /**
   * Whether the bundle is acceptable as written: there are no errors. Warnings do not count.
   *
   * @return {@code true} when nothing stops a simulation
   */
  public boolean isValid() {
    return errors().isEmpty();
  }

  /**
   * The issues that stop a simulation.
   *
   * @return the errors
   */
  public List<PolicyIssue> errors() {
    return issues.stream().filter(i -> i.level() == PolicyIssue.Level.ERROR).toList();
  }

  /**
   * The issues that do not stop a simulation but that the author should read.
   *
   * @return the warnings
   */
  public List<PolicyIssue> warnings() {
    return issues.stream().filter(i -> i.level() == PolicyIssue.Level.WARNING).toList();
  }

  /**
   * The parsed bundle.
   *
   * @return the bundle, or empty when the text did not parse
   */
  public Optional<PolicyBundle> parsedBundle() {
    return Optional.ofNullable(bundle);
  }
}
