package com.debthunter.controlplane.governance.simulation;

import com.debthunter.policy.PolicyBundle;
import java.util.Optional;

/**
 * Where the content of a policy bundle that is already in force comes from. The catalogue records a
 * bundle's version and digest but not its text, so the simulator asks here for the bundle a
 * repository is currently held to.
 *
 * <p>A version with no available content is a normal answer, not a failure: the simulator then
 * still says what the candidate would do, and says that it cannot say what changed.
 */
public interface PolicyBundleSource {

  /**
   * The bundle published under {@code version}.
   *
   * @param version the policy bundle version
   * @return the bundle, or empty if its content is not available
   */
  Optional<PolicyBundle> find(String version);
}
