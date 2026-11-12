package com.debthunter.controlplane.ai.remediation;

import java.util.Optional;

/**
 * Proposes next steps for a finding.
 *
 * <p>Failure contract: an advisor never throws for an expected failure (a model that is down, a
 * refusal); it returns empty and the next advisor is tried.
 */
public interface RemediationAdvisor {

  /**
   * Proposes a remediation.
   *
   * @param request the finding to address
   * @return a proposal, or empty if this advisor cannot produce one now
   */
  Optional<RemediationProposal> propose(RemediationRequest request);
}
