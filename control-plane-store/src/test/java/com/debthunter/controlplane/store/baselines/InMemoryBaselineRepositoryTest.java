package com.debthunter.controlplane.store.baselines;

class InMemoryBaselineRepositoryTest extends BaselineRepositoryContract {

  @Override
  protected BaselineRegistryRepository newRepository() {
    return new InMemoryBaselineRegistryRepository();
  }
}
