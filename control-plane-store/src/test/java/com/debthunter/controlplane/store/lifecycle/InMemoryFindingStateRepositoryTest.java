package com.debthunter.controlplane.store.lifecycle;

class InMemoryFindingStateRepositoryTest extends FindingStateRepositoryContract {

  @Override
  protected FindingStateRepository newRepository() {
    return new InMemoryFindingStateRepository();
  }
}
