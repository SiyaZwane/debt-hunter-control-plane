package com.debthunter.controlplane.store.policy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/** A {@link PolicyCatalogueRepository} held in memory, for tests and local development. */
public final class InMemoryPolicyCatalogueRepository implements PolicyCatalogueRepository {

  private final Map<String, PolicyBundleRecord> bundles = new TreeMap<>();

  @Override
  public synchronized void save(PolicyBundleRecord bundle) {
    bundles.put(bundle.version(), bundle);
  }

  @Override
  public synchronized Optional<PolicyBundleRecord> find(String version) {
    return Optional.ofNullable(bundles.get(version));
  }

  @Override
  public synchronized List<PolicyBundleRecord> all() {
    return List.copyOf(bundles.values());
  }
}
