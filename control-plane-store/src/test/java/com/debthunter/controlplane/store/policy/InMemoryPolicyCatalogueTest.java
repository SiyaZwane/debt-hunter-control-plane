package com.debthunter.controlplane.store.policy;

class InMemoryPolicyCatalogueTest extends PolicyCatalogueContract {

  @Override
  protected PolicyCatalogueRepository newRepository() {
    return new InMemoryPolicyCatalogueRepository();
  }
}
