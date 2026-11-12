package com.debthunter.controlplane.ai.remediation;

import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Tries advisors in order and uses the first proposal. A failing advisor is skipped. */
public final class ChainedRemediationAdvisor implements RemediationAdvisor {

  private static final Logger LOG = LoggerFactory.getLogger(ChainedRemediationAdvisor.class);

  private final List<RemediationAdvisor> advisors;

  /**
   * Creates the chain.
   *
   * @param advisors the advisors, most preferred first
   */
  public ChainedRemediationAdvisor(List<RemediationAdvisor> advisors) {
    this.advisors = List.copyOf(advisors);
  }

  @Override
  public Optional<RemediationProposal> propose(RemediationRequest request) {
    for (RemediationAdvisor advisor : advisors) {
      try {
        Optional<RemediationProposal> proposal = advisor.propose(request);
        if (proposal.isPresent()) {
          return proposal;
        }
      } catch (RuntimeException e) {
        LOG.warn(
            "Advisor {} failed for {}",
            advisor.getClass().getSimpleName(),
            request.finding().id(),
            e);
      }
    }
    return Optional.empty();
  }
}
