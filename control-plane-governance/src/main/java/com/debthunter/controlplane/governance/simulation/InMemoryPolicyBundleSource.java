package com.debthunter.controlplane.governance.simulation;

import com.debthunter.policy.PolicyBundle;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** A {@link PolicyBundleSource} held in memory, for tests and for wiring before publication. */
public final class InMemoryPolicyBundleSource implements PolicyBundleSource {

  private final Map<String, PolicyBundle> bundles = new ConcurrentHashMap<>();

  /**
   * Makes a bundle available under its own version.
   *
   * @param bundle the bundle
   * @return this source, for chaining
   */
  public InMemoryPolicyBundleSource with(PolicyBundle bundle) {
    bundles.put(bundle.version(), bundle);
    return this;
  }

  @Override
  public Optional<PolicyBundle> find(String version) {
    return Optional.ofNullable(bundles.get(version));
  }
}
