package com.debthunter.controlplane.governance.simulation;

import com.debthunter.controlplane.governance.policy.ValidationReport;

/** Either the simulation ran, or the candidate was turned away before it could. */
public sealed interface SimulationOutcome {

  /**
   * The candidate had errors, so nothing was simulated and nothing was read.
   *
   * @param validation what is wrong with it
   */
  record Rejected(ValidationReport validation) implements SimulationOutcome {}

  /**
   * The simulation ran.
   *
   * @param report the impact
   */
  record Completed(ImpactReport report) implements SimulationOutcome {}
}
