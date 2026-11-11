package com.debthunter.controlplane.store.policy;

import java.util.List;
import java.util.Optional;

/** Persistence for the policy catalogue. */
public interface PolicyCatalogueRepository {

  /** Inserts or replaces a bundle record. */
  void save(PolicyBundleRecord bundle);

  /** Looks a version up. */
  Optional<PolicyBundleRecord> find(String version);

  /** Every version, in a stable order. */
  List<PolicyBundleRecord> all();
}
