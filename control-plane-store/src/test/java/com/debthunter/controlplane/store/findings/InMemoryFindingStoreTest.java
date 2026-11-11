package com.debthunter.controlplane.store.findings;

class InMemoryFindingStoreTest extends PseudonymisedFindingStoreContract {

  @Override
  protected PseudonymisedFindingStore newStore() {
    return new InMemoryPseudonymisedFindingStore();
  }
}
